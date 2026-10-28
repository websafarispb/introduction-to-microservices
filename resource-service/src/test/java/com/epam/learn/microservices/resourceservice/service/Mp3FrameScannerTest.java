package com.epam.learn.microservices.resourceservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4: detection of real MP3 audio frames, independent of Tika.
 */
class Mp3FrameScannerTest {

    // --- frame header decoding ---

    @ParameterizedTest(name = "{0} -> {1} bytes")
    @CsvSource({
            "FFFBB000, 626",  // MPEG-1 Layer III, 192 kbps, 44.1 kHz (synthetic files)
            "FFFBB064, 626",  // same, joint stereo (header of the original course sample)
            "FFFBB200, 627",  // same with padding bit
            "FFFB9000, 417",  // MPEG-1 Layer III, 128 kbps, 44.1 kHz
            "FFFB9400, 384",  // MPEG-1 Layer III, 128 kbps, 48 kHz
            "FFF39000, 261",  // MPEG-2 Layer III, 80 kbps, 22.05 kHz: 72 * 80000 / 22050
            "FFE39000, 522",  // MPEG-2.5 Layer III, 80 kbps, 11.025 kHz: 72 * 80000 / 11025
    })
    void decodesLayerIiiFrameLength(String header, int expectedLength) {
        assertThat(Mp3FrameScanner.frameLength(HexFormat.of().parseHex(header), 0)).isEqualTo(expectedLength);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "FFFDB000, Layer II (MP2) is not MP3",
            "FFFFB000, Layer I is not MP3",
            "FFEBB000, reserved MPEG version",
            "FFFB0000, free-format bitrate",
            "FFFBF000, invalid bitrate index 15",
            "FFFBBC00, reserved sample rate",
            "FFDBB000, incomplete frame sync",
            "49443303, ID3 tag header",
    })
    void rejectsHeadersThatAreNotLayerIii(String header, String reason) {
        assertThat(Mp3FrameScanner.frameLength(HexFormat.of().parseHex(header), 0)).as(reason).isZero();
    }

    @Test
    void headerAtEndOfContentIsIgnored() {
        assertThat(Mp3FrameScanner.frameLength(HexFormat.of().parseHex("FFFBB0"), 0)).isZero();
    }

    // --- coverage of the audio area ---

    @Test
    void syntheticMp3IsFullyCovered() {
        byte[] mp3 = Mp3MetadataExtractorTest.withRequiredTags().id3v1("t", "a", "b", "2025").build();

        assertThat(Mp3FrameScanner.audioCoverage(mp3)).isEqualTo(1.0);
        assertThat(Mp3FrameScanner.looksLikeMp3Audio(mp3)).isTrue();
    }

    @Test
    void junkBeforeTheAudioIsTolerated() {
        byte[] junk = new byte[5000];
        new Random(9).nextBytes(junk);
        byte[] mp3 = Mp3MetadataExtractorTest.withRequiredTags().junkBeforeAudio(junk).build();

        assertThat(Mp3FrameScanner.audioCoverage(mp3)).isGreaterThan(0.95);
        assertThat(Mp3FrameScanner.looksLikeMp3Audio(mp3)).isTrue();
    }

    @Test
    void id3TagWithoutFramesHasNoAudio() {
        byte[] id3Only = Mp3MetadataExtractorTest.withRequiredTags().audioFrames(0).build();

        assertThat(Mp3FrameScanner.audioCoverage(id3Only)).isZero();
    }

    // --- valid structures that must not be rejected ---

    @Test
    void variableBitrateFramesAreFullyCovered() {
        byte[] vbr = SyntheticMp3.builder()
                .moreFrames(0xFF, 0xFB, 0x90, 0x00, 417, 50)   // 128 kbps
                .moreFrames(0xFF, 0xFB, 0xB2, 0x00, 627, 50)   // 192 kbps, padded
                .moreFrames(0xFF, 0xFB, 0xE0, 0x00, 1044, 50)  // 320 kbps
                .moreFrames(0xFF, 0xFB, 0x10, 0x00, 104, 50)   // 32 kbps
                .build();

        assertThat(Mp3FrameScanner.audioCoverage(vbr)).isEqualTo(1.0);
    }

    @Test
    void mpeg2AndMpeg25LayerIiiAreAccepted() {
        byte[] mpeg2 = SyntheticMp3.builder().moreFrames(0xFF, 0xF3, 0x90, 0x00, 261, 300).build();
        byte[] mpeg25 = SyntheticMp3.builder().moreFrames(0xFF, 0xE3, 0x90, 0x00, 522, 300).build();

        assertThat(Mp3FrameScanner.audioCoverage(mpeg2)).isEqualTo(1.0);
        assertThat(Mp3FrameScanner.audioCoverage(mpeg25)).isEqualTo(1.0);
    }

    @Test
    void id3v2FooterIsNotCountedAsAudio() {
        byte[] mp3 = Mp3MetadataExtractorTest.withRequiredTags().id3v2Footer().build();

        assertThat(Mp3FrameScanner.audioCoverage(mp3)).isEqualTo(1.0);
    }

    @Test
    void truncatedLastFrameIsTolerated() {
        byte[] full = Mp3MetadataExtractorTest.withRequiredTags().build();
        byte[] truncated = java.util.Arrays.copyOf(full, full.length - 300);

        assertThat(Mp3FrameScanner.looksLikeMp3Audio(truncated)).isTrue();
    }

    @Test
    void typicalTrailingApeTagIsTolerated() {
        byte[] apeTag = new byte[2048];
        System.arraycopy("APETAGEX".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, apeTag, 0, 8);
        byte[] mp3 = Mp3MetadataExtractorTest.withRequiredTags().trailingBytes(apeTag).build();

        assertThat(Mp3FrameScanner.audioCoverage(mp3)).isGreaterThan(0.98);
        assertThat(Mp3FrameScanner.looksLikeMp3Audio(mp3)).isTrue();
    }

    @Test
    void knownLimitationVeryShortAudioWithLargeTrailingTagIsRejected() {
        // Documented limitation: trailing tags other than ID3v1 count as non-audio bytes.
        // 5 frames (0.13 s, 3130 bytes) followed by a 2 KiB tag cover only ~60 % of the audio area.
        byte[] mp3 = SyntheticMp3.builder().audioFrames(5).trailingBytes(new byte[2048]).build();

        assertThat(Mp3FrameScanner.audioCoverage(mp3)).isLessThan(Mp3FrameScanner.MIN_AUDIO_COVERAGE);
        assertThat(Mp3FrameScanner.looksLikeMp3Audio(mp3)).isFalse();
    }

    // --- not MP3 ---

    @Test
    void layerIiFramesAreNotMp3() {
        byte[] mp2 = SyntheticMp3.builder().frameHeader(0xFF, 0xFD, 0xB0, 0x00).audioFrames(299).build();

        assertThat(Mp3FrameScanner.looksLikeMp3Audio(mp2)).isFalse();
    }

    // ID3v2.2 flags %ab000000, ID3v2.3 %abc00000, ID3v2.4 %abcd0000: the zero bits must be cleared
    @ParameterizedTest(name = "ID3v2.{0} flags 0x{1} -> undefined: {2}")
    @CsvSource({"2, 40, false", "2, 20, true",
            "3, 20, false", "3, 10, true", "3, 02, true",
            "4, 10, false", "4, 08, true", "4, 01, true"})
    void id3v2FlagBitsUndefinedForTheTagVersionAreRejected(int version, String flags, boolean undefined) {
        byte[] mp3 = SyntheticMp3.withId3v2Flags(version, Integer.parseInt(flags, 16), 0, 10);

        assertThat(Mp3FrameScanner.hasUndefinedId3v2Flags(mp3)).isEqualTo(undefined);
        assertThat(Mp3FrameScanner.looksLikeMp3Audio(mp3)).isEqualTo(!undefined);
    }

    @Test
    void randomDataIsNeverAcceptedInManyTrials() {
        // Before the scanner existed, more than half of these inputs were accepted via Tika alone.
        for (int seed = 0; seed < 500; seed++) {
            for (int size : new int[]{512, 4096, 65536}) {
                byte[] random = new byte[size];
                new Random(seed).nextBytes(random);
                assertThat(Mp3FrameScanner.looksLikeMp3Audio(random))
                        .as("seed %d, %d bytes, coverage %.3f", seed, size, Mp3FrameScanner.audioCoverage(random))
                        .isFalse();
            }
        }
    }
}
