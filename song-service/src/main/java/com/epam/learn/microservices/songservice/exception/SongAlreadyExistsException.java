package com.epam.learn.microservices.songservice.exception;

public class SongAlreadyExistsException extends RuntimeException {

    public SongAlreadyExistsException(Long id, Throwable cause) {
        super("Metadata for resource ID=%d already exists".formatted(id), cause);
    }
}
