package com.epam.learn.microservices.resourceservice.client;

import com.epam.learn.microservices.resourceservice.dto.SongMetadataRequest;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application's {@link SongServiceClient} bean resolves {@code http://song-service} through Spring Cloud
 * LoadBalancer. Eureka is disabled in the test profile, so two local stub servers are registered as
 * {@code song-service} instances with the simple discovery client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "song-service.read-timeout=500ms")
@ActiveProfiles("test")
class SongServiceClientLoadBalancingTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final long SLOW_ID = 99;

    private static final SongInstance FIRST = new SongInstance();
    private static final SongInstance SECOND = new SongInstance();

    @Autowired
    private SongServiceClient client;

    @Autowired
    @LoadBalanced
    private ObjectProvider<RestClient.Builder> builder;

    @DynamicPropertySource
    static void songServiceInstances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.song-service[0].uri", FIRST::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.song-service[1].uri", SECOND::uri);
    }

    @AfterAll
    static void stopInstances() {
        FIRST.stop();
        SECOND.stop();
    }

    @BeforeEach
    void resetCounters() {
        FIRST.requests.set(0);
        SECOND.requests.set(0);
    }

    @Test
    void requestsAreSpreadOverRegisteredInstances() {
        for (long id = 1; id <= 4; id++) {
            assertThat(client.create(request(id))).isEqualTo(id);
        }
        assertThat(client.delete(List.of(5L, 6L))).containsExactly(5L, 6L);

        assertThat(FIRST.requests.get()).isPositive();
        assertThat(SECOND.requests.get()).isPositive();
        assertThat(FIRST.requests.get() + SECOND.requests.get()).isEqualTo(5);
    }

    @Test
    void readTimeoutStillAppliesThroughTheLoadBalancer() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.create(request(SLOW_ID)))
                .isInstanceOfSatisfying(SongServiceException.class, e -> assertThat(e.status()).isNull())
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void serviceWithoutRegisteredInstanceFailsBeforeAnyRequest() {
        var unregistered = new SongServiceClient(builder.getObject(), "http://unregistered-service",
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        // The load balancer's own exception, deliberately not mapped to SongServiceException
        assertThatThrownBy(() -> unregistered.create(request(7)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("No instances available for unregistered-service");
        assertThat(FIRST.requests.get() + SECOND.requests.get()).isZero();
    }

    private static SongMetadataRequest request(long id) {
        return new SongMetadataRequest(id, "Title", "Artist", "Album", "00:07", "2025");
    }

    /** A minimal Song Service stand-in that echoes ids and counts requests. */
    private static final class SongInstance {

        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();

        SongInstance() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/songs", this::handle);
            // A slow request must not block the next ones
            server.setExecutor(executor);
            server.start();
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String response;
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = ID.matcher(body);
                long id = matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
                if (id == SLOW_ID) {
                    sleep(2000);
                }
                response = "{\"id\":" + id + "}";
            } else {
                String ids = exchange.getRequestURI().getQuery().replace("id=", "");
                response = "{\"ids\":[" + ids + "]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
