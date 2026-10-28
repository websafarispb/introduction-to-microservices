package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.dto.Mp3Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the extractor against the original course sample files, which are NOT part of this repository.
 * Runs only when {@code -Dsample.mp3.dir=<directory containing the two sample files>} is given; skipped otherwise.
 */
@EnabledIfSystemProperty(named = "sample.mp3.dir", matches = ".+")
class Mp3MetadataExtractorSampleTest {

    private final Mp3MetadataExtractor extractor = new Mp3MetadataExtractor();

    private static byte[] sample(String fileName) throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("sample.mp3.dir"), fileName));
    }

    @Test
    void validSampleMatchesApiSpecification() throws IOException {
        // Expected values: GET /songs/{id} for the uploaded valid sample in the API response specification.
        assertThat(extractor.extract(sample("valid-sample-with-required-tags.mp3")))
                .isEqualTo(new Mp3Metadata("Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    @Test
    void sampleWithMissingTagsHasEmptyArtistAndAlbumFromId3v1() throws IOException {
        assertThat(extractor.extract(sample("invalid-sample-with-missed-tags.mp3")))
                .isEqualTo(new Mp3Metadata("Valid Title", "", "", "00:07", "2025"));
    }

    @Test
    void syntheticFileWithSameTagsYieldsSameMetadataAsValidSample() throws IOException {
        // Same ID3v2 frames and ID3v1 fields as the original, same number of audio frames.
        byte[] synthetic = SyntheticMp3.builder()
                .id3v2("TALB", "Test Album").id3v2("TPE1", "Test Artist").id3v2("TCON", "Test Genre")
                .id3v2("TIT2", "Test Title").id3v2("TRCK", "1").id3v2("TYER", "2025")
                .audioFrames(Mp3MetadataExtractorTest.SAMPLE_FRAMES)
                .id3v1("Test Title", "Test Artist", "Test Album", "2025").build();

        assertThat(extractor.extract(synthetic))
                .isEqualTo(extractor.extract(sample("valid-sample-with-required-tags.mp3")));
    }

    @Test
    void syntheticFileWithSameTagsYieldsSameMetadataAsSampleWithMissingTags() throws IOException {
        byte[] synthetic = SyntheticMp3.builder()
                .id3v2("TCON", "Test Genre").id3v2("TIT2", "Valid Title").id3v2("TRCK", "1").id3v2("TYER", "2025")
                .audioFrames(Mp3MetadataExtractorTest.SAMPLE_FRAMES)
                .id3v1("Valid Title", "", "", "2025").build();

        assertThat(extractor.extract(synthetic))
                .isEqualTo(extractor.extract(sample("invalid-sample-with-missed-tags.mp3")));
    }
}
