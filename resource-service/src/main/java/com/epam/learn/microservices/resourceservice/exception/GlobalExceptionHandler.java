package com.epam.learn.microservices.resourceservice.exception;

import com.epam.learn.microservices.resourceservice.dto.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.nio.charset.StandardCharsets;

/**
 * Maps every error to the unified response format of the API contract. Spring MVC's own exceptions
 * (405, unknown path, ...) keep their status but get our body; internal details are only logged.
 *
 * <p>Error bodies set {@code Content-Type: application/json} explicitly: otherwise a client sending
 * {@code Accept: audio/mpeg} gets an empty 500 instead of the JSON error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler({InvalidIdException.class, InvalidFileFormatException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Song Service failures. Only a validation error with the expected structure (e.g. missing MP3 tags) is a
     * client error: it is returned as 400 with the Song Service's body unchanged. Everything else (409, 5xx,
     * no response, unexpected body) is a server-side failure and becomes the generic 500; the downstream body
     * is neither returned nor logged.
     */
    @ExceptionHandler(SongServiceException.class)
    public ResponseEntity<?> handleSongService(SongServiceException e) {
        if (e.status() != null && e.status().value() == 400 && isValidationErrorBody(e.responseBody())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body(e.responseBody().getBytes(StandardCharsets.UTF_8));
        }
        if (e.status() == null) {
            log.error("Song Service call failed: {}", e.getMessage(), e.getCause());
        } else {
            log.error("Song Service call failed: {}", e.getMessage()); // cause would include the response body
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error has occurred");
    }

    /** Delete cascade failures are always server errors; the details are logged by the service. */
    @ExceptionHandler(MetadataDeletionException.class)
    public ResponseEntity<ErrorResponse> handleMetadataDeletion(MetadataDeletionException e) {
        log.error("Request failed: {}", e.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error has occurred");
    }

    /** {"errorMessage": "Validation error", "details": {non-empty, string values}, "errorCode": "400"} only. */
    private boolean isValidationErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject() || root.size() != 3) {
                return false;
            }
            JsonNode details = root.get("details");
            if (!"Validation error".equals(text(root, "errorMessage")) || !"400".equals(text(root, "errorCode"))
                    || details == null || !details.isObject() || details.isEmpty()) {
                return false;
            }
            for (JsonNode value : details) {
                if (!value.isTextual()) {
                    return false;
                }
            }
            return true;
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error has occurred");
    }

    /** Spring MVC exceptions: keep status and headers (e.g. Allow), replace the body. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Request failed", e);
        }
        String message = e instanceof HttpMessageNotReadableException
                ? "Invalid request body"
                : reasonPhrase(status);
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(message, String.valueOf(status.value())));
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(message, String.valueOf(status.value())));
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Request failed";
    }
}
