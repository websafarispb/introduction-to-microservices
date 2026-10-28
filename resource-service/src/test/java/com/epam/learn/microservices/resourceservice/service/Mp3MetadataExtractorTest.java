package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.dto.Mp3Metadata;
import com.epam.learn.microservices.resourceservice.exception.InvalidFileFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3 (duration) and T4 (extraction / validation) with synthetic MP3 files built in memory.
 */
class Mp3MetadataExtractorTest {

    /** Same frame count as the original sample: 299 * 1152 / 44100 = 7.8106 s. */
    static final int SAMPLE_FRAMES = 299;

    private static final String INVALID_MP3 =
            "Invalid file format: content is not a valid MP3. Only MP3 files are allowed";

    private final Mp3MetadataExtractor extractor = new Mp3MetadataExtractor();

    static SyntheticMp3 withRequiredTags() {
        return SyntheticMp3.builder()
                .id3v2("TIT2", "Test Title").id3v2("TPE1", "Test Artist").id3v2("TALB", "Test Album")
                .id3v2("TYER", "2025")
                .audioFrames(SAMPLE_FRAMES);
    }

    // --- valid MP3 ---

    @Test
    void extractsRequiredTagsAndTruncatedDuration() {
        assertThat(extractor.extract(withRequiredTags().build()))
                .isEqualTo(new Mp3Metadata("Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    @Test
    void absentArtistAndAlbumAreNullAndDoNotFailExtraction() {
        byte[] mp3 = SyntheticMp3.builder()
                .id3v2("TIT2", "Valid Title").id3v2("TYER", "2025")
                .audioFrames(SAMPLE_FRAMES).build();

        assertThat(extractor.extract(mp3))
                .isEqualTo(new Mp3Metadata("Valid Title", null, null, "00:07", "2025"));
    }

    @Test
    void emptyId3v1ArtistAndAlbumAreReturnedAsEmptyStrings() {
        // Mirrors the tag-less course sample: no TPE1/TALB frames, but an ID3v1 tag with empty fields.
        byte[] mp3 = SyntheticMp3.builder()
                .id3v2("TIT2", "Valid Title").id3v2("TYER", "2025")
                .audioFrames(SAMPLE_FRAMES)
                .id3v1("Valid Title", "", "", "2025").build();

        assertThat(extractor.extract(mp3))
                .isEqualTo(new Mp3Metadata("Valid Title", "", "", "00:07", "2025"));
    }

    @Test
    void mp3WithoutAnyTagsIsStillValidAudio() {
        assertThat(extractor.extract(SyntheticMp3.builder().audioFrames(SAMPLE_FRAMES).build()))
                .isEqualTo(new Mp3Metadata(null, null, null, "00:07", null));
    }

    // --- invalid content ---

    @Test
    void id3TagWithoutMpegFramesIsRejected() {
        byte[] id3Only = withRequiredTags().audioFrames(0).build();

        assertThatThrownBy(() -> extractor.extract(id3Only))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    @Test
    void corruptedFrameHeadersAreRejected() {
        byte[] valid = withRequiredTags().build();
        int audioStart = valid.length - SAMPLE_FRAMES * SyntheticMp3.FRAME_LENGTH;
        byte[] corrupted = SyntheticMp3.withCorruptedFrameHeaders(valid, audioStart, SAMPLE_FRAMES);

        assertThatThrownBy(() -> extractor.extract(corrupted))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    @Test
    void textIsRejected() {
        assertThatThrownBy(() -> extractor.extract("invalid data".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    @Test
    void emptyContentIsRejected() {
        assertThatThrownBy(() -> extractor.extract(new byte[0]))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    @Test
    void randomBytesAreRejected() {
        byte[] random = new byte[4096];
        new Random(1).nextBytes(random);

        assertThatThrownBy(() -> extractor.extract(random))
                .isInstanceOf(InvalidFileFormatException.class);
    }

    @Test
    void zeroFilledContentIsRejected() {
        byte[] zeros = new byte[SAMPLE_FRAMES * SyntheticMp3.FRAME_LENGTH];
        Arrays.fill(zeros, (byte) 0);

        assertThatThrownBy(() -> extractor.extract(zeros))
                .isInstanceOf(InvalidFileFormatException.class);
    }

    // --- tagged non-MP3 content ---

    static Stream<Arguments> otherFormatSignatures() {
        return Stream.of(
                Arguments.of("WAV", "RIFF\0\0\1\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1)),
                Arguments.of("AAC (ADTS)", new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80}),
                Arguments.of("PNG", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}),
                Arguments.of("text", "invalid data".getBytes(StandardCharsets.US_ASCII)));
    }

    @ParameterizedTest(name = "ID3 tag + {0}")
    @MethodSource("otherFormatSignatures")
    void validId3TagFollowedByAnotherFormatIsRejected(String format, byte[] signature) {
        // 64 KiB of random data contains enough accidental frame headers for Mp3Parser to report a duration
        byte[] payload = Arrays.copyOf(signature, signature.length + 65536);
        byte[] random = new byte[65536];
        new Random(7).nextBytes(random);
        System.arraycopy(random, 0, payload, signature.length, random.length);
        byte[] file = withRequiredTags().audioFrames(0).junkBeforeAudio(payload).build();

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    @Test
    void junkBetweenId3TagAndAudioIsTolerated() {
        byte[] junk = new byte[5000];
        new Random(9).nextBytes(junk);

        assertThat(extractor.extract(withRequiredTags().junkBeforeAudio(junk).build()))
                .isEqualTo(new Mp3Metadata("Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    // --- MPEG audio that is not MP3, and frame sync without chained frames ---

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "MPEG-1 Layer II with ID3 tag, true,  0xFD",
            "MPEG-1 Layer II,              false, 0xFD",
            "MPEG-2 Layer I,               false, 0xF7",
    })
    void otherMpegAudioLayersAreRejected(String description, boolean tagged, String secondHeaderByte) {
        SyntheticMp3 builder = tagged ? withRequiredTags() : SyntheticMp3.builder().audioFrames(SAMPLE_FRAMES);
        byte[] file = builder.frameHeader(0xFF, Integer.decode(secondHeaderByte), 0xB0, 0x00).build();

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    static Stream<Arguments> frameSyncFollowedByRandomData() {
        byte[] random = new byte[65536];
        new Random(7).nextBytes(random);
        byte[] sync = {(byte) 0xFF, (byte) 0xFB};
        byte[] secondTag = SyntheticMp3.builder().id3v2("TXXX", "x").build();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        return Stream.of(
                Arguments.of("frame sync + random", concat(sync, random)),
                Arguments.of("ID3 tag + frame sync + random",
                        withRequiredTags().audioFrames(0).junkBeforeAudio(concat(sync, random)).build()),
                Arguments.of("ID3 tag + CRLF + frame sync + random",
                        withRequiredTags().audioFrames(0).junkBeforeAudio(concat("\r\n".getBytes(), sync, random)).build()),
                Arguments.of("ID3 tag + second ID3 tag + PNG + random",
                        withRequiredTags().audioFrames(0).junkBeforeAudio(concat(secondTag, png, random)).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameSyncFollowedByRandomData")
    void frameSyncWithoutChainedFramesIsRejected(String description, byte[] file) {
        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    // --- malformed ID3v2 tags: 400, never an unchecked exception (HTTP 500) ---

    @ParameterizedTest(name = "flags 0x{0}, extended header size 0x{1}")
    @CsvSource({
            "02, FFFFFFFF",  // Tika read this as a negative extended header size and failed with an unchecked exception
            "01, 00000000",
            "08, 00000000",
    })
    void id3v2TagWithUndefinedFlagsIsRejected(String flags, String extendedHeaderSize) {
        byte[] file = SyntheticMp3.withId3v2Flags(3, Integer.parseInt(flags, 16),
                (int) Long.parseLong(extendedHeaderSize, 16), SAMPLE_FRAMES);

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    @ParameterizedTest(name = "declared size {0} bytes too small")
    @CsvSource({"8", "12"})  // Tika failed on both with an unchecked exception
    void id3v2TagSizeEndingInsideAFrameHeaderIsRejected(int bytes) {
        byte[] file = SyntheticMp3.withId3v2SizeReducedBy(withRequiredTags().build(), bytes);

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(InvalidFileFormatException.class)
                .hasMessage(INVALID_MP3);
    }

    // --- metadata around the audio ---

    @Test
    void largeCoverArtInId3TagIsAccepted() {
        // APIC frame: text encoding, MIME type, picture type, description, then 1 MiB of picture data
        byte[] header = "\0image/jpeg\0\3\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] picture = Arrays.copyOf(header, header.length + (1 << 20));
        byte[] data = new byte[1 << 20];
        new Random(3).nextBytes(data);
        System.arraycopy(data, 0, picture, header.length, data.length);

        assertThat(extractor.extract(withRequiredTags().id3v2Frame("APIC", picture).build()))
                .isEqualTo(new Mp3Metadata("Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    static Stream<Arguments> validAudioLayouts() {
        byte[] sample = withRequiredTags().build();
        return Stream.of(
                // 1 * 1152 / 44100 = 0.03 s
                Arguments.of("single 320 kbps frame with ID3v1", SyntheticMp3.builder().id3v2("TIT2", "Test Title")
                        .moreFrames(0xFF, 0xFB, 0xE0, 0x00, 1044, 1).id3v1("Test Title", "", "", "2025").build(), "00:00"),
                // 150 * 1152 / 44100 = 3.92 s
                Arguments.of("variable bitrate 32-320 kbps", SyntheticMp3.builder()
                        .moreFrames(0xFF, 0xFB, 0x90, 0x00, 417, 50)    // 128 kbps
                        .moreFrames(0xFF, 0xFB, 0xE0, 0x00, 1044, 50)   // 320 kbps
                        .moreFrames(0xFF, 0xFB, 0x10, 0x00, 104, 50)    // 32 kbps
                        .build(), "00:03"),
                // 300 * 576 / 22050 = 7.84 s and 300 * 576 / 11025 = 15.67 s
                Arguments.of("MPEG-2 Layer III", SyntheticMp3.builder().moreFrames(0xFF, 0xF3, 0x90, 0x00, 261, 300).build(), "00:07"),
                Arguments.of("MPEG-2.5 Layer III", SyntheticMp3.builder().moreFrames(0xFF, 0xE3, 0x90, 0x00, 522, 300).build(), "00:15"),
                Arguments.of("ID3v2.4 tag with footer", withRequiredTags().id3v2Footer().build(), "00:07"),
                Arguments.of("truncated last frame", Arrays.copyOf(sample, sample.length - 300), "00:07"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validAudioLayouts")
    void validAudioLayoutIsAccepted(String layout, byte[] mp3, String expectedDuration) {
        assertThat(extractor.extract(mp3).duration()).isEqualTo(expectedDuration);
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    // --- duration (T3) ---

    @ParameterizedTest
    @CsvSource({
            "7.810635089874268, 00:07",   // original sample: rounding would give 00:08
            "0.026122, 00:00",
            "0.999, 00:00",
            "59.999, 00:59",
            "60.0, 01:00",
            "61.5, 01:01",
            "3599.99, 59:59",
            "3600.0, 60:00",
            "6000.0, 100:00"
    })
    void durationIsTruncatedToWholeSeconds(double seconds, String expected) {
        assertThat(Mp3MetadataExtractor.formatDuration(seconds)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1, 00:00", "38, 00:00", "39, 00:01", "299, 00:07", "2296, 00:59", "2297, 01:00"})
    void durationFollowsFrameCount(int frames, String expected) {
        // 1152 samples / 44100 Hz per frame: 38 frames = 0.993 s, 39 = 1.019 s, 2296 = 59.977 s, 2297 = 60.003 s
        byte[] mp3 = SyntheticMp3.builder().audioFrames(frames).build();

        assertThat(extractor.extract(mp3).duration()).isEqualTo(expected);
    }
}
