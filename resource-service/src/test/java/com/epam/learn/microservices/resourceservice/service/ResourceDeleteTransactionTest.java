package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.client.SongServiceClient;
import com.epam.learn.microservices.resourceservice.entity.Resource;
import com.epam.learn.microservices.resourceservice.exception.MetadataDeletionException;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import com.epam.learn.microservices.resourceservice.repository.ResourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.3: delete cascade (local DELETE + Song Service DELETE in one local transaction) against the real
 * resource-db (schema resource_test). The Song Service client is mocked to produce every downstream outcome.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ResourceDeleteTransactionTest {

    private static final String GENERIC_500 = """
            {"errorMessage": "An internal server error has occurred", "errorCode": "500"}""";
    private static final String VALIDATION_SHAPED_400 = "{\"errorMessage\":\"Validation error\","
            + "\"details\":{\"id\":\"secret detail\"},\"errorCode\":\"400\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private SongServiceClient songServiceClient;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        resourceRepository.deleteAllInBatch();
        RecordingStatementInspector.clear();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private long store() {
        return resourceRepository.save(new Resource(new byte[]{1, 2, 3})).getId();
    }

    private void songServiceDeletesEverything() {
        when(songServiceClient.delete(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Count through a separate pool connection that does not take part in any Spring transaction. */
    private long committedRows(List<Long> ids) throws SQLException {
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.createStatement().executeQuery(
                     "select count(*) from resource_test.resource where id in (" + in + ")")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // --- forwarding only what was deleted ---

    @Test
    void forwardsOnlyDeletedIdsDeduplicatedInRequestOrder() throws Exception {
        songServiceDeletesEverything();
        long a = store();
        long b = store();
        long kept = store();

        List<Long> deleted = resourceService.delete(b + ",99998," + a + "," + b + ",101");

        assertThat(deleted).containsExactly(b, a);
        verify(songServiceClient).delete(List.of(b, a));
        assertThat(committedRows(List.of(a, b))).isZero();
        assertThat(committedRows(List.of(kept))).isEqualTo(1);
    }

    @Test
    void unknownIdsAreNeverForwardedSoStandaloneMetadataSurvives() throws Exception {
        mvc.perform(delete("/resources").param("id", "654321,101,102"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ids\":[]}", JsonCompareMode.STRICT));

        verify(songServiceClient, never()).delete(any());
    }

    @Test
    void invalidCsvNeverReachesDatabaseOrSongService() throws Exception {
        long id = store();

        mvc.perform(delete("/resources").param("id", id + ",V")).andExpect(status().isBadRequest());
        mvc.perform(delete("/resources").param("id", "1,".repeat(100) + "1")).andExpect(status().isBadRequest());

        verify(songServiceClient, never()).delete(any());
        assertThat(committedRows(List.of(id))).isEqualTo(1);
    }

    @Test
    void forwardedIdsNeverExceedTheRequestLength() throws Exception {
        songServiceDeletesEverything();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(store());
        }
        // leading zeros make the request longer than the forwarded list
        String csv = ids.stream().map(id -> "0000" + id).collect(Collectors.joining(","));
        assertThat(csv.length()).isLessThanOrEqualTo(200);
        var forwarded = new AtomicReference<List<Long>>();
        when(songServiceClient.delete(any())).thenAnswer(invocation -> {
            forwarded.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        resourceService.delete(csv);

        String forwardedCsv = forwarded.get().stream().map(String::valueOf).collect(Collectors.joining(","));
        assertThat(forwarded.get()).containsExactlyElementsOf(ids);
        assertThat(forwardedCsv.length()).isLessThanOrEqualTo(csv.length());
    }

    // --- transaction boundary ---

    @Test
    void localDeleteIsVisibleInsideTransactionButNotCommittedWhenSongServiceIsCalled() throws Exception {
        long id = store();
        var insideTransaction = new AtomicReference<Boolean>();
        var existsInSameTransaction = new AtomicReference<Boolean>();
        var rowsSeenByOtherConnection = new AtomicReference<Long>();
        when(songServiceClient.delete(any())).thenAnswer(invocation -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            existsInSameTransaction.set(resourceRepository.existsById(id));
            rowsSeenByOtherConnection.set(committedRows(List.of(id)));
            return invocation.getArgument(0);
        });

        resourceService.delete(String.valueOf(id));

        assertThat(insideTransaction.get()).isTrue();
        assertThat(existsInSameTransaction.get()).as("deleted in the same transaction").isFalse();
        assertThat(rowsSeenByOtherConnection.get()).as("delete not yet committed (read committed)").isEqualTo(1);
        assertThat(committedRows(List.of(id))).as("after commit").isZero();
    }

    // --- downstream failures: rollback and generic 500, never a pass-through ---

    @Test
    void everyDownstreamFailureRollsBackAndIsGeneric500(CapturedOutput output) throws Exception {
        var failures = List.<RuntimeException>of(
                new SongServiceException("m", HttpStatus.SERVICE_UNAVAILABLE, "maintenance", null),
                new SongServiceException("m", HttpStatus.BAD_REQUEST,
                        "{\"errorMessage\":\"Invalid ID format: 'x'. Only positive integers are allowed\",\"errorCode\":\"400\"}",
                        null),
                new SongServiceException("m", HttpStatus.BAD_REQUEST, VALIDATION_SHAPED_400, null),
                new SongServiceException("Song Service unreachable during delete song metadata (outcome unknown)",
                        null, null, new ResourceAccessException("Read timed out")),
                new SongServiceException("Song Service returned no ids for delete of [1]", null, null, null),
                new IllegalStateException("secret internal detail"));
        long id = store();

        for (RuntimeException failure : failures) {
            org.mockito.Mockito.doThrow(failure).when(songServiceClient).delete(any());

            mvc.perform(delete("/resources").param("id", String.valueOf(id)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                    .andExpect(content().json(GENERIC_500, JsonCompareMode.STRICT));

            assertThat(committedRows(List.of(id))).as("rolled back after %s", failure).isEqualTo(1);
        }
        assertThat(output).doesNotContain("maintenance").doesNotContain("secret detail")
                .contains("rejected delete for resources [" + id + "] with status 503")
                .contains("failed without a definite answer").contains("retrying the delete is safe");
    }

    @Test
    void serviceMethodThrowsMetadataDeletionExceptionNotTheDownstreamException() {
        long id = store();
        when(songServiceClient.delete(any())).thenThrow(
                new SongServiceException("m", HttpStatus.BAD_REQUEST, VALIDATION_SHAPED_400, null));

        assertThatThrownBy(() -> resourceService.delete(String.valueOf(id)))
                .isInstanceOf(MetadataDeletionException.class)
                .hasMessage("Song Service did not confirm metadata deletion for resources [" + id + "]");
    }

    @Test
    void fewerMetadataDeletedIsSuccessWithWarning(CapturedOutput output) throws Exception {
        long a = store();
        long b = store();
        when(songServiceClient.delete(any())).thenReturn(List.of(a));

        assertThat(resourceService.delete(a + "," + b)).containsExactly(a, b);

        assertThat(committedRows(List.of(a, b))).isZero();
        assertThat(output).contains("Deleted resources [" + b + "] had no song metadata to delete");
    }

    @Test
    void retryAfterAmbiguousFailureSucceeds() throws Exception {
        long id = store();
        when(songServiceClient.delete(any()))
                .thenThrow(new SongServiceException("outcome unknown", null, null, null))
                .thenReturn(List.of()); // metadata already gone: the timed-out request was executed after all

        assertThatThrownBy(() -> resourceService.delete(String.valueOf(id))).isInstanceOf(MetadataDeletionException.class);
        assertThat(committedRows(List.of(id))).isEqualTo(1);

        assertThat(resourceService.delete(String.valueOf(id))).containsExactly(id);
        assertThat(committedRows(List.of(id))).isZero();
        verify(songServiceClient, times(2)).delete(List.of(id));
    }

    // --- SQL and concurrency ---

    @Test
    void existenceCheckTakesNoLockAndDeleteIsOneStatement() {
        songServiceDeletesEverything();
        long id = store();
        RecordingStatementInspector.clear();

        resourceService.delete(String.valueOf(id));

        List<String> sql = RecordingStatementInspector.statements();
        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).startsWith("select r1_0.id from resource_test.resource r1_0 where r1_0.id in")
                .doesNotContain("for update");
        assertThat(sql.get(1)).startsWith("delete from resource_test.resource").contains(" in ");
    }

    /** Waits until PostgreSQL reports a backend of this database waiting for a lock. */
    private void awaitLockWaiter() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'");
                 ResultSet r = s.executeQuery()) {
                r.next();
                if (r.getLong(1) > 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("second delete did not block on the row lock");
    }

    @Test
    void concurrentDeleteOfSameIdBlocksOnRowLockThenOverstates_knownLimitation() throws Exception {
        long id = store();
        var firstInSongCall = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(songServiceClient.delete(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstInSongCall.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS);
            }
            return invocation.getArgument(0);
        });

        Future<List<Long>> first = executor.submit(() -> resourceService.delete(String.valueOf(id)));
        assertThat(firstInSongCall.await(10, TimeUnit.SECONDS)).isTrue();
        Future<List<Long>> second = executor.submit(() -> resourceService.delete(String.valueOf(id)));
        awaitLockWaiter();
        assertThat(second.isDone()).as("second delete waits while the first holds the row lock").isFalse();
        releaseFirst.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).containsExactly(id);
        // Known limitation: the second request's unlocked SELECT saw the row before the first committed;
        // its DELETE then removes 0 rows, yet it still forwards and reports the id.
        assertThat(second.get(10, TimeUnit.SECONDS)).containsExactly(id);
        verify(songServiceClient, times(2)).delete(List.of(id));
        assertThat(committedRows(List.of(id))).isZero();
    }

    @Test
    void concurrentDeleteAfterFirstRollsBackDeletesCorrectly() throws Exception {
        long id = store();
        var firstInSongCall = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(songServiceClient.delete(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstInSongCall.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS);
                throw new SongServiceException("m", HttpStatus.SERVICE_UNAVAILABLE, null, null);
            }
            return invocation.getArgument(0);
        });

        Future<List<Long>> first = executor.submit(() -> resourceService.delete(String.valueOf(id)));
        assertThat(firstInSongCall.await(10, TimeUnit.SECONDS)).isTrue();
        Future<List<Long>> second = executor.submit(() -> resourceService.delete(String.valueOf(id)));
        awaitLockWaiter();
        releaseFirst.countDown();

        assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(MetadataDeletionException.class);
        assertThat(second.get(10, TimeUnit.SECONDS)).containsExactly(id);
        assertThat(committedRows(List.of(id))).isZero();
    }
}
