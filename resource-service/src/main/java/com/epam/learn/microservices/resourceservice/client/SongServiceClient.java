package com.epam.learn.microservices.resourceservice.client;

import com.epam.learn.microservices.resourceservice.dto.SongMetadataRequest;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Synchronous REST client for the Song Service.
 *
 * <p>The base URL names the Eureka service id ({@code http://song-service}); the load-balanced builder
 * picks a registered instance for each request. If none is known yet, the load balancer's
 * {@code IllegalStateException} propagates unchanged (nothing was sent): the caller's transaction rolls
 * back and the API answers with the generic 500.
 *
 * <p>Timeouts apply per connection attempt and per socket read; they do not bound the total duration of a
 * call. No automatic retries: if a POST's outcome is unknown and it did succeed, a repeated POST with the
 * same id would be rejected by the Song Service as a duplicate (409) and report a failure that did not happen.
 */
@Component
public class SongServiceClient {

    private final RestClient restClient;

    @Autowired
    public SongServiceClient(@LoadBalanced RestClient.Builder builder,
            @Value("${song-service.url}") String baseUrl,
            @Value("${song-service.connect-timeout}") Duration connectTimeout,
            @Value("${song-service.read-timeout}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** For tests that bind a mock server to the client. */
    SongServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Creates the song metadata ({@code POST /songs}).
     *
     * @return the id returned by the Song Service, always equal to {@code request.id()}
     * @throws SongServiceException on an error status, when no response was received, or when a successful
     *                              response does not contain the requested id (protocol/integrity failure)
     */
    public Long create(SongMetadataRequest request) {
        CreatedResponse created = call("create song metadata", () -> restClient.post()
                .uri("/songs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CreatedResponse.class));
        Long returnedId = created == null ? null : created.id();
        if (!request.id().equals(returnedId)) {
            throw new SongServiceException("Song Service returned id %s for resource id %d"
                    .formatted(returnedId, request.id()), null, null, null);
        }
        return returnedId;
    }

    /**
     * Deletes song metadata ({@code DELETE /songs?id=...}). No request is sent for an empty list.
     *
     * @return the ids the Song Service reports as deleted
     * @throws SongServiceException on an error status, when no response was received, or when a successful
     *                              response contains no ids
     */
    public List<Long> delete(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        DeletedResponse deleted = call("delete song metadata", () -> restClient.delete()
                .uri(uri -> uri.path("/songs").queryParam("id", csv).build())
                .retrieve()
                .body(DeletedResponse.class));
        if (deleted == null || deleted.ids() == null) {
            throw new SongServiceException("Song Service returned no ids for delete of " + ids, null, null, null);
        }
        return deleted.ids();
    }

    private static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new SongServiceException("Song Service failed to " + operation + ": " + e.getStatusCode(),
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new SongServiceException("Song Service unreachable during " + operation + " (outcome unknown)",
                    null, null, e);
        } catch (RestClientException e) {
            throw new SongServiceException("Song Service call failed during " + operation, null, null, e);
        }
    }

    record CreatedResponse(Long id) {
    }

    record DeletedResponse(List<Long> ids) {
    }
}
