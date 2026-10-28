package com.epam.learn.microservices.songservice.dto;

import java.util.Map;

/**
 * Validation error body: {@code {"errorMessage": "Validation error", "details": {field: message}, "errorCode": "400"}}.
 */
public record ValidationErrorResponse(String errorMessage, Map<String, String> details, String errorCode) {
}
