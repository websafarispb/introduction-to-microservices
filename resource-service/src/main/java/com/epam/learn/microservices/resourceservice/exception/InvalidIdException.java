package com.epam.learn.microservices.resourceservice.exception;

/**
 * An id or comma-separated id list supplied by the client is not valid.
 */
public class InvalidIdException extends RuntimeException {

    public InvalidIdException(String message) {
        super(message);
    }
}
