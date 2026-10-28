package com.epam.learn.microservices.songservice.exception;

public class SongNotFoundException extends RuntimeException {

    public SongNotFoundException(Long id) {
        super("Song metadata for ID=%d not found".formatted(id));
    }
}
