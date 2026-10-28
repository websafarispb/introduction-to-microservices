package com.epam.learn.microservices.resourceservice.dto;

/**
 * Tags extracted from an MP3 file, unmodified except for the duration, which is converted to mm:ss.
 * A tag that is absent from the file is {@code null}; an empty tag is {@code ""}.
 */
public record Mp3Metadata(String name, String artist, String album, String duration, String year) {
}
