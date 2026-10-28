package com.epam.learn.microservices.resourceservice.exception;

/**
 * The uploaded content is not an MP3 file (wrong Content-Type or no MPEG audio data).
 */
public class InvalidFileFormatException extends RuntimeException {

    public InvalidFileFormatException(String message) {
        super(message);
    }

    public InvalidFileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
