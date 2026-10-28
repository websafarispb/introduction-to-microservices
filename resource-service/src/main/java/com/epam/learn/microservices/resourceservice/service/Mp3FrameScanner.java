package com.epam.learn.microservices.resourceservice.service;

/**
 * Measures how much of a file's audio area is made of consecutive MPEG Layer III (MP3) frames.
 *
 * <p>Needed because Tika's {@code Mp3Parser} accepts almost anything: it reports MPEG audio as soon as a
 * few bytes anywhere look like a frame header (more than half of random 4 KiB inputs pass). Real MP3 files
 * are a chain of frames, each starting where the previous one ends, so they cover (nearly) the whole area
 * between the ID3v2 tag and the ID3v1 tag; random data covers only a small fraction.
 *
 * <p>Observed coverage: course samples 1.00, a sample with 5000 junk bytes inserted 0.97,
 * random 4 KiB inputs at most 0.39, larger random inputs at most 0.06.
 *
 * <p>Scope and limitations (a plausibility check, not an MP3 decoder):
 * <ul>
 *   <li>Each frame length comes from its own header, so variable bitrate files chain correctly; a
 *       Xing/Info header is itself a regular frame. MPEG 1, 2 and 2.5 Layer III are accepted.</li>
 *   <li>Excluded from the audio area: a leading ID3v2 tag (including its footer) and a trailing ID3v1 tag.
 *       Other metadata (APEv2, Lyrics3, an appended ID3v2 tag, padding beyond the declared tag size)
 *       counts as non-audio. Harmless for normal files, but very short audio with a large trailing tag
 *       can fall below the threshold and be rejected.</li>
 *   <li>Free-format bitrate (index 0) frames are not recognised, so such rare files are rejected.</li>
 *   <li>A leading ID3v2 tag with a flag bit set that its version requires to be cleared is rejected,
 *       see {@link #hasUndefinedId3v2Flags}.</li>
 *   <li>A fake frame header inside junk can make the scan jump into a real frame; it then resynchronises
 *       byte by byte, losing at most about one frame of coverage.</li>
 *   <li>The 0.8 threshold is heuristic, chosen from the measurements above; frame content is not decoded,
 *       so a crafted sequence of valid headers is accepted.</li>
 * </ul>
 */
final class Mp3FrameScanner {

    /** Minimum share of the audio area that must be covered by chained MP3 frames. */
    static final double MIN_AUDIO_COVERAGE = 0.8;

    private static final int[] KBPS_MPEG1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] KBPS_MPEG2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    /** Indexed by the two version bits: 0 = MPEG 2.5, 1 = reserved, 2 = MPEG 2, 3 = MPEG 1. */
    private static final int[][] SAMPLE_RATES = {{11025, 12000, 8000}, null, {22050, 24000, 16000}, {44100, 48000, 32000}};

    private Mp3FrameScanner() {
    }

    static boolean looksLikeMp3Audio(byte[] content) {
        return !hasUndefinedId3v2Flags(content) && audioCoverage(content) >= MIN_AUDIO_COVERAGE;
    }

    /**
     * True for a leading ID3v2 tag with a header flag set that its version requires to be cleared
     * (v2.2: {@code %ab000000}, v2.3: {@code %abc00000}, v2.4: {@code %abcd0000}; other versions as v2.4).
     *
     * <p>Tika's {@code ID3v2Frame} reads the undefined bit 0x02 as "extended header present" and allocates the size
     * in the next 4 bytes, failing on a negative one. Only the leading tag is checked: Tika also reads a further
     * ID3v2 tag that follows it and allocates that tag's declared size (capped at 50 MB by Tika) before the parse
     * fails. Such files are still rejected, but this check does not bound Tika's memory use.
     */
    static boolean hasUndefinedId3v2Flags(byte[] content) {
        if (content.length < 10 || content[0] != 'I' || content[1] != 'D' || content[2] != '3') {
            return false;
        }
        int undefinedFlags = switch (content[3]) {
            case 2 -> 0x3F;
            case 3 -> 0x1F;
            default -> 0x0F;
        };
        return (content[5] & undefinedFlags) != 0;
    }

    /** Share (0..1) of the bytes between the ID3v2 and ID3v1 tags that belong to chained Layer III frames. */
    static double audioCoverage(byte[] content) {
        int start = audioStart(content);
        int end = Math.max(start, audioEnd(content));
        if (end == start) {
            return 0;
        }
        long covered = 0;
        int position = start;
        while (position < end) {
            int length = frameLength(content, position);
            if (length > 4 && position + length <= end) {
                covered += length;
                position += length;
            } else {
                position++; // not a frame start: resynchronise byte by byte
            }
        }
        return (double) covered / (end - start);
    }

    /** Length in bytes of the Layer III frame whose header starts at {@code position}, or 0 if there is none. */
    static int frameLength(byte[] content, int position) {
        if (position + 4 > content.length) {
            return 0;
        }
        int b1 = content[position + 1] & 0xFF;
        int b2 = content[position + 2] & 0xFF;
        if ((content[position] & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) {
            return 0; // no 11-bit frame sync
        }
        int version = (b1 >> 3) & 0x3;
        int layer = (b1 >> 1) & 0x3;
        int bitrateIndex = (b2 >> 4) & 0xF;
        int sampleRateIndex = (b2 >> 2) & 0x3;
        int padding = (b2 >> 1) & 0x1;
        if (version == 1 || layer != 1 || sampleRateIndex == 3) {
            return 0; // reserved version, not Layer III, or reserved sample rate
        }
        int kbps = (version == 3 ? KBPS_MPEG1 : KBPS_MPEG2)[bitrateIndex];
        if (kbps == 0) {
            return 0; // free-format or invalid bitrate
        }
        int sampleRate = SAMPLE_RATES[version][sampleRateIndex];
        return (version == 3 ? 144 : 72) * kbps * 1000 / sampleRate + padding;
    }

    /** Offset after an ID3v2 tag (header + size + optional footer), or 0 without one. */
    private static int audioStart(byte[] content) {
        if (content.length < 10 || content[0] != 'I' || content[1] != 'D' || content[2] != '3') {
            return 0;
        }
        int size = ((content[6] & 0x7F) << 21) | ((content[7] & 0x7F) << 14)
                | ((content[8] & 0x7F) << 7) | (content[9] & 0x7F);
        boolean footer = (content[5] & 0x10) != 0;
        return (int) Math.min(content.length, 10L + size + (footer ? 10 : 0));
    }

    /** Offset of a trailing 128-byte ID3v1 tag, or the content length without one. */
    private static int audioEnd(byte[] content) {
        int tag = content.length - 128;
        boolean id3v1 = tag >= 0 && content[tag] == 'T' && content[tag + 1] == 'A' && content[tag + 2] == 'G';
        return id3v1 ? tag : content.length;
    }
}
