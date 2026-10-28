package com.epam.learn.microservices.songservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /songs}.
 *
 * <p>Each field has exactly one "required" constraint ({@code @NotNull}) and one format
 * constraint. Bean Validation format constraints treat {@code null} as valid, so a missing
 * field reports only "... is required", while a present-but-invalid value (including "")
 * reports only the format message.
 */
public record SongRequest(

        @NotNull(message = "Id is required")
        @Positive(message = "Id must be a positive number")
        Long id,

        @NotNull(message = "Song name is required")
        @Size(min = 1, max = 100, message = "Song name must be between 1 and 100 characters")
        String name,

        @NotNull(message = "Artist name is required")
        @Size(min = 1, max = 100, message = "Artist name must be between 1 and 100 characters")
        String artist,

        @NotNull(message = "Album name is required")
        @Size(min = 1, max = 100, message = "Album name must be between 1 and 100 characters")
        String album,

        @NotNull(message = "Duration is required")
        @Pattern(regexp = "\\d{2}:[0-5]\\d", message = "Duration must be in mm:ss format with leading zeros")
        String duration,

        @NotNull(message = "Year is required")
        @Pattern(regexp = "(19|20)\\d{2}", message = "Year must be between 1900 and 2099")
        String year
) {
}
