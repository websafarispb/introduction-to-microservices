package com.epam.learn.microservices.resourceservice.dto;

/**
 * Request body of the Song Service's {@code POST /songs}. The id is the Resource ID.
 *
 * <p>Tag values are passed on exactly as extracted (absent tags as {@code null}, empty tags as {@code ""});
 * the Song Service validates them. Only the duration was converted (seconds to mm:ss) during extraction.
 */
public record SongMetadataRequest(Long id, String name, String artist, String album, String duration, String year) {

    public static SongMetadataRequest of(Long resourceId, Mp3Metadata metadata) {
        return new SongMetadataRequest(resourceId, metadata.name(), metadata.artist(), metadata.album(),
                metadata.duration(), metadata.year());
    }
}
