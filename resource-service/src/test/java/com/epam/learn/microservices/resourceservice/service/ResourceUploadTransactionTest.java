package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.client.SongServiceClient;
import com.epam.learn.microservices.resourceservice.dto.SongMetadataRequest;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import com.epam.learn.microservices.resourceservice.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.2: upload = resource INSERT + Song Service call in one local transaction, against the real resource-db
 * (schema resource_test). The Song Service client is mocked to produce every downstream outcome.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ResourceUploadTransactionTest {

    private static final String GENERIC_500 = """
            {"errorMessage": "An internal server error has occurred", "errorCode": "500"}""";
    private static final String VALIDATION_BODY = "{\"errorMessage\":\"Validation error\",\"details\":{"
            + "\"artist\":\"Artist name must be between 1 and 100 characters\","
            + "\"album\":\"Album name must be between 1 and 100 characters\"},\"errorCode\":\"400\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private SongServiceClient songServiceClient;

    @BeforeEach
    void cleanDatabase() {
        resourceRepository.deleteAllInBatch();
    }

    private static byte[] validMp3() {
        return SyntheticMp3.builder()
                .id3v2("TIT2", "Test Title").id3v2("TPE1", "Test Artist").id3v2("TALB", "Test Album")
                .id3v2("TYER", "2025").audioFrames(299).build();
    }

    private ResultActions upload(byte[] body) throws Exception {
        return mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "audio/mpeg").content(body));
    }

    private static ResultActions expectGeneric500(ResultActions result) throws Exception {
        return result.andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().json(GENERIC_500, JsonCompareMode.STRICT));
    }

    private static SongServiceException downstream(HttpStatus status, String body) {
        return new SongServiceException("Song Service failed to create song metadata: " + status, status, body, null);
    }

    /** Count through a separate pool connection that does not take part in any Spring transaction. */
    private long committedRowsWithId(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from resource_test.resource where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    // --- commit ---

    @Test
    void successCommitsResourceAndSendsExactMetadataWithResourceId() throws Exception {
        String response = upload(validMp3()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(response.replaceAll("\\D", ""));

        verify(songServiceClient).create(
                new SongMetadataRequest(id, "Test Title", "Test Artist", "Test Album", "00:07", "2025"));
        assertThat(committedRowsWithId(id)).isEqualTo(1);
    }

    @Test
    void insertIsVisibleInsideTheTransactionButNotCommittedWhenSongServiceIsCalled() throws Exception {
        var insideTransaction = new AtomicReference<Boolean>();
        var visibleToSameTransaction = new AtomicReference<Boolean>();
        var visibleToOtherConnection = new AtomicReference<Long>();
        var isolation = new AtomicReference<String>();
        when(songServiceClient.create(any())).thenAnswer(invocation -> {
            long id = invocation.<SongMetadataRequest>getArgument(0).id();
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            visibleToSameTransaction.set(resourceRepository.existsById(id)); // joins the transaction
            visibleToOtherConnection.set(committedRowsWithId(id));
            try (Connection c = dataSource.getConnection(); ResultSet r =
                    c.createStatement().executeQuery("show transaction_isolation")) {
                r.next();
                isolation.set(r.getString(1));
            }
            return id;
        });

        String response = upload(validMp3()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(response.replaceAll("\\D", ""));

        assertThat(isolation.get()).isEqualTo("read committed");
        assertThat(insideTransaction.get()).isTrue();
        assertThat(visibleToSameTransaction.get()).isTrue();
        assertThat(visibleToOtherConnection.get()).as("uncommitted insert seen by another connection").isZero();
        assertThat(committedRowsWithId(id)).as("committed after the Song Service call").isEqualTo(1);
    }

    // --- validation happens before the transaction ---

    @ParameterizedTest
    @ValueSource(strings = {"invalid data", ""})
    void invalidMp3NeverCallsSongService(String body) throws Exception {
        upload(body.getBytes(StandardCharsets.UTF_8)).andExpect(status().isBadRequest());

        verify(songServiceClient, never()).create(any());
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void wrongContentTypeNeverCallsSongService() throws Exception {
        mvc.perform(post("/resources").header(HttpHeaders.CONTENT_TYPE, "application/json").content(validMp3()))
                .andExpect(status().isBadRequest());

        verify(songServiceClient, never()).create(any());
        assertThat(resourceRepository.count()).isZero();
    }

    // --- downstream validation error: 400 passed on, rollback ---

    @Test
    void downstreamValidationErrorIsPassedOnVerbatimAndResourceIsRolledBack(CapturedOutput output) throws Exception {
        when(songServiceClient.create(any())).thenThrow(downstream(HttpStatus.BAD_REQUEST, VALIDATION_BODY));

        upload(validMp3())
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().bytes(VALIDATION_BODY.getBytes(StandardCharsets.UTF_8)));

        assertThat(resourceRepository.count()).isZero();
        assertThat(output).contains("rejected metadata for resource").contains("status 400")
                .doesNotContain("Artist name must be between");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "[]",
            "{}",
            "{\"errorMessage\":\"Validation error\",\"errorCode\":\"400\"}",
            "{\"errorMessage\":\"Validation error\",\"details\":{},\"errorCode\":\"400\"}",
            "{\"errorMessage\":\"Validation error\",\"details\":{\"artist\":1},\"errorCode\":\"400\"}",
            "{\"errorMessage\":\"Validation error\",\"details\":{\"artist\":\"x\"},\"errorCode\":400}",
            "{\"errorMessage\":\"Other\",\"details\":{\"artist\":\"x\"},\"errorCode\":\"400\"}",
            "{\"errorMessage\":\"Validation error\",\"details\":{\"artist\":\"x\"},\"errorCode\":\"400\",\"trace\":\"t\"}",
            "{\"errorMessage\":\"Invalid request body\",\"errorCode\":\"400\"}"
    })
    void unexpectedDownstream400BodyBecomesGeneric500(String body) throws Exception {
        when(songServiceClient.create(any())).thenThrow(downstream(HttpStatus.BAD_REQUEST, body));

        expectGeneric500(upload(validMp3()));
        assertThat(resourceRepository.count()).isZero();
    }

    // --- every other downstream failure: generic 500, rollback, nothing leaked ---

    @Test
    void downstreamConflictBecomesGeneric500(CapturedOutput output) throws Exception {
        String body = "{\"errorMessage\":\"Metadata for resource ID=1 already exists\",\"errorCode\":\"409\"}";
        when(songServiceClient.create(any())).thenThrow(downstream(HttpStatus.CONFLICT, body));

        expectGeneric500(upload(validMp3()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("already exists"))));
        assertThat(resourceRepository.count()).isZero();
        assertThat(output).contains("status 409").doesNotContain("already exists");
    }

    @Test
    void downstreamServerErrorBecomesGeneric500() throws Exception {
        when(songServiceClient.create(any())).thenThrow(downstream(HttpStatus.SERVICE_UNAVAILABLE, "down for maintenance"));

        expectGeneric500(upload(validMp3()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("maintenance"))));
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void noResponseIsAmbiguousLoggedWithResourceIdAndRolledBack(CapturedOutput output) throws Exception {
        when(songServiceClient.create(any())).thenThrow(new SongServiceException(
                "Song Service unreachable during create song metadata (outcome unknown)", null, null,
                new ResourceAccessException("Read timed out")));

        expectGeneric500(upload(validMp3()));

        assertThat(resourceRepository.count()).isZero();
        assertThat(output).contains("failed without a definite answer").contains("outcome unknown")
                .contains("may exist as an orphan; check GET /songs/");
    }

    @Test
    void idMismatchBecomesGeneric500() throws Exception {
        when(songServiceClient.create(any())).thenThrow(
                new SongServiceException("Song Service returned id 2 for resource id 1", null, null, null));

        expectGeneric500(upload(validMp3()));
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void unexpectedClientExceptionBecomesGeneric500() throws Exception {
        when(songServiceClient.create(any())).thenThrow(new IllegalStateException("secret internal detail"));

        expectGeneric500(upload(validMp3()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        assertThat(resourceRepository.count()).isZero();
    }

    @Test
    void serviceStaysUsableAfterRollback() throws Exception {
        when(songServiceClient.create(any()))
                .thenThrow(downstream(HttpStatus.SERVICE_UNAVAILABLE, null))
                .thenAnswer(invocation -> invocation.<SongMetadataRequest>getArgument(0).id());

        expectGeneric500(upload(validMp3()));
        String response = upload(validMp3()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        long id = Long.parseLong(response.replaceAll("\\D", ""));
        assertThat(id).isPositive();
        assertThat(resourceRepository.count()).isEqualTo(1);
        assertThat(committedRowsWithId(id)).isEqualTo(1);
    }
}
