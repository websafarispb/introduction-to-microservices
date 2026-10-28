package com.epam.learn.microservices.resourceservice.dto;

/**
 * Simple error body: {@code {"errorMessage": "...", "errorCode": "404"}}. The code is a string by contract.
 */
public record ErrorResponse(String errorMessage, String errorCode) {
}
