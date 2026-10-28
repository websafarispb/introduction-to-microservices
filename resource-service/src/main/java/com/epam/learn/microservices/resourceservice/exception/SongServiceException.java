package com.epam.learn.microservices.resourceservice.exception;

import org.springframework.http.HttpStatusCode;

/**
 * A call to the Song Service did not succeed: either it answered with an error status, or no response was
 * received (connection refused, timeout), in which case {@link #status()} is {@code null} and the outcome
 * of the call is unknown.
 */
public class SongServiceException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    public SongServiceException(String message, HttpStatusCode status, String responseBody, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.responseBody = responseBody;
    }

    /** Status returned by the Song Service, or {@code null} if no response was received. */
    public HttpStatusCode status() {
        return status;
    }

    /** Response body returned by the Song Service, or {@code null} if no response was received. */
    public String responseBody() {
        return responseBody;
    }
}
