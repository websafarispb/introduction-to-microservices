package com.epam.learn.microservices.resourceservice.client;

import com.epam.learn.microservices.resourceservice.dto.Mp3Metadata;
import com.epam.learn.microservices.resourceservice.dto.SongMetadataRequest;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SongServiceClientTest {

    private static final String BASE_URL = "http://song-service.test";
    private static final SongMetadataRequest REQUEST =
            new SongMetadataRequest(1L, "Test Title", "Test Artist", "Test Album", "00:07", "2025");

    private MockRestServiceServer server;
    private SongServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SongServiceClient(builder.build());
    }

    // --- mapping ---

    @Test
    void metadataIsMappedUnchangedWithResourceIdAsSongId() {
        var metadata = new Mp3Metadata("Valid Title", "", null, "00:07", "2025");

        assertThat(SongMetadataRequest.of(42L, metadata))
                .isEqualTo(new SongMetadataRequest(42L, "Valid Title", "", null, "00:07", "2025"));
    }

    // --- create: POST /songs ---

    @Test
    void createSendsExactJsonAndReturnsId() {
        server.expect(requestTo(BASE_URL + "/songs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(content().json("""
                        {"id": 1, "name": "Test Title", "artist": "Test Artist", "album": "Test Album",
                         "duration": "00:07", "year": "2025"}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.create(REQUEST)).isEqualTo(1L);
        server.verify();
    }

    @Test
    void absentTagsAreSentAsNullAndEmptyTagsAsEmptyStrings() {
        server.expect(requestTo(BASE_URL + "/songs"))
                .andExpect(content().string(
                        "{\"id\":7,\"name\":\"Valid Title\",\"artist\":\"\",\"album\":null,"
                                + "\"duration\":\"00:07\",\"year\":\"2025\"}"))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));

        client.create(new SongMetadataRequest(7L, "Valid Title", "", null, "00:07", "2025"));
        server.verify();
    }

    @Test
    void successfulResponseWithoutIdIsAProtocolFailure() {
        for (String body : new String[]{"{}", "{\"id\":null}"}) {
            server.reset();
            server.expect(requestTo(BASE_URL + "/songs")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.create(REQUEST))
                    .as("response %s", body)
                    .isInstanceOfSatisfying(SongServiceException.class, e -> {
                        assertThat(e.status()).isNull();
                        assertThat(e.getMessage()).isEqualTo("Song Service returned id null for resource id 1");
                    });
        }
    }

    @Test
    void successfulResponseWithEmptyBodyIsAProtocolFailure() {
        server.expect(requestTo(BASE_URL + "/songs")).andRespond(withSuccess());

        assertThatThrownBy(() -> client.create(REQUEST))
                .isInstanceOfSatisfying(SongServiceException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Song Service returned id null for resource id 1"));
    }

    @Test
    void successfulResponseWithDifferentIdIsAnIntegrityFailure() {
        server.expect(requestTo(BASE_URL + "/songs")).andRespond(withSuccess("{\"id\":2}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.create(REQUEST))
                .isInstanceOfSatisfying(SongServiceException.class, e -> {
                    assertThat(e.status()).isNull();
                    assertThat(e.getMessage()).isEqualTo("Song Service returned id 2 for resource id 1");
                });
    }

    @Test
    void downstreamValidationErrorKeepsStatusAndBody() {
        String body = """
                {"errorMessage":"Validation error","details":{"artist":"Artist name must be between 1 and 100 characters"},"errorCode":"400"}""";
        server.expect(requestTo(BASE_URL + "/songs"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(body));

        assertThatThrownBy(() -> client.create(REQUEST))
                .isInstanceOfSatisfying(SongServiceException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.responseBody()).isEqualTo(body);
                });
    }

    @Test
    void downstreamConflictKeepsStatusAndBody() {
        String body = "{\"errorMessage\":\"Metadata for resource ID=1 already exists\",\"errorCode\":\"409\"}";
        server.expect(requestTo(BASE_URL + "/songs"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(body));

        assertThatThrownBy(() -> client.create(REQUEST))
                .isInstanceOfSatisfying(SongServiceException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.responseBody()).isEqualTo(body);
                });
    }

    @Test
    void downstreamServerErrorKeepsStatus() {
        server.expect(requestTo(BASE_URL + "/songs")).andRespond(withServerError());

        assertThatThrownBy(() -> client.create(REQUEST))
                .isInstanceOfSatisfying(SongServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    // --- delete: DELETE /songs?id= ---

    @Test
    void deleteSendsCommaSeparatedIdsAndReturnsDeletedIds() {
        server.expect(requestTo(BASE_URL + "/songs?id=3,1,2"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"ids\":[3,1]}", MediaType.APPLICATION_JSON));

        assertThat(client.delete(List.of(3L, 1L, 2L))).containsExactly(3L, 1L);
        server.verify();
    }

    @Test
    void deleteOfEmptyListSendsNoRequest() {
        assertThat(client.delete(List.of())).isEmpty();
        server.verify(); // no expectations: any request would have failed the test
    }

    @Test
    void deleteResponseWithoutIdsIsAProtocolFailure() {
        server.expect(requestTo(BASE_URL + "/songs?id=1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.delete(List.of(1L)))
                .isInstanceOfSatisfying(SongServiceException.class, e -> {
                    assertThat(e.status()).isNull();
                    assertThat(e.getMessage()).isEqualTo("Song Service returned no ids for delete of [1]");
                });
    }

    @Test
    void deleteDownstreamErrorKeepsStatus() {
        server.expect(requestTo(BASE_URL + "/songs?id=1")).andRespond(withServerError());

        assertThatThrownBy(() -> client.delete(List.of(1L)))
                .isInstanceOfSatisfying(SongServiceException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    // --- exceptions that are not Song Service failures ---

    @Test
    void unrelatedIllegalStateExceptionIsNotTreatedAsSongServiceFailure() {
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new IllegalStateException("unrelated failure");
        });
        var failing = new SongServiceClient(builder, BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(() -> failing.create(REQUEST))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("unrelated failure");
        assertThatThrownBy(() -> failing.delete(List.of(1L)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("unrelated failure");
    }

    // --- connection failures over real sockets (no mock server) ---

    private static SongServiceClient realClient(int port, Duration readTimeout) {
        return new SongServiceClient(RestClient.builder(), "http://127.0.0.1:" + port, Duration.ofSeconds(2), readTimeout);
    }

    @Test
    void connectionRefusedHasNoStatusAndKeepsCause() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> realClient(closedPort, Duration.ofSeconds(5)).create(REQUEST))
                .isInstanceOfSatisfying(SongServiceException.class, e -> {
                    assertThat(e.status()).isNull();
                    assertThat(e.responseBody()).isNull();
                    assertThat(e.getMessage()).contains("outcome unknown");
                })
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void serverThatNeverAnswersHitsReadTimeout() throws Exception {
        try (var server = new ServerSocket(0)) {
            // Accepts the connection (in the OS backlog) but never writes a response.
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignoredException) {
                    // test finished
                }
            });
            long start = System.nanoTime();

            assertThatThrownBy(() -> realClient(server.getLocalPort(), Duration.ofMillis(300)).create(REQUEST))
                    .isInstanceOfSatisfying(SongServiceException.class, e -> assertThat(e.status()).isNull())
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);

            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMillis).isBetween(300L, 3_000L);
            acceptor.interrupt();
        }
    }
}
