package com.epam.learn.microservices.songservice.dto;

/**
 * Response body of {@code GET /songs/{id}}: exactly the six fields of the API contract.
 */
public record SongResponse(
        Long id,
        String name,
        String artist,
        String album,
        String duration,
        String year
) {
}
