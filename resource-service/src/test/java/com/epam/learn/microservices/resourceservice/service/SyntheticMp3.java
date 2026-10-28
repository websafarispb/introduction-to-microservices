package com.epam.learn.microservices.resourceservice.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds small MP3 files in memory for tests, so no real audio file has to be committed.
 *
 * <p>Audio: silent MPEG-1 Layer III frames, 192 kbps, 44.1 kHz, stereo, no padding
 * (header {@code FF FB B0 00}, 144 * 192000 / 44100 = 626 bytes per frame, 1152 samples = 26.12 ms each).
 * Tags: an optional ID3v2.3 tag (or ID3v2.4 with footer) and an optional 128-byte ID3v1 tag.
 */
public final class SyntheticMp3 {

    public static final int FRAME_LENGTH = 626;
    private static final byte[] FRAME_HEADER = {(byte) 0xFF, (byte) 0xFB, (byte) 0xB0, 0x00};

    private final Map<String, byte[]> id3v2Frames = new LinkedHashMap<>();
    private String[] id3v1; // title, artist, album, year
    private int audioFrames;
    private byte[] frameHeader = FRAME_HEADER;
    private byte[] junkBeforeAudio = new byte[0];
    private final ByteArrayOutputStream extraFrames = new ByteArrayOutputStream();
    private byte[] trailingBytes = new byte[0];
    private boolean id3v2Footer;

    public static SyntheticMp3 builder() {
        return new SyntheticMp3();
    }

    public SyntheticMp3 id3v2(String frameId, String text) {
        byte[] content = (" " + text).getBytes(StandardCharsets.ISO_8859_1);
        content[0] = 0; // text encoding: ISO-8859-1
        return id3v2Frame(frameId, content);
    }

    /** Adds an ID3v2 frame with raw content, e.g. an APIC picture. */
    public SyntheticMp3 id3v2Frame(String frameId, byte[] content) {
        id3v2Frames.put(frameId, content);
        return this;
    }

    public SyntheticMp3 id3v1(String title, String artist, String album, String year) {
        id3v1 = new String[]{title, artist, album, year};
        return this;
    }

    public SyntheticMp3 audioFrames(int count) {
        audioFrames = count;
        return this;
    }

    /** Uses a different 4-byte frame header (frames stay {@link #FRAME_LENGTH} bytes long). */
    public SyntheticMp3 frameHeader(int b0, int b1, int b2, int b3) {
        frameHeader = new byte[]{(byte) b0, (byte) b1, (byte) b2, (byte) b3};
        return this;
    }

    /** Inserts arbitrary bytes between the ID3v2 tag and the first audio frame. */
    public SyntheticMp3 junkBeforeAudio(byte[] junk) {
        junkBeforeAudio = junk;
        return this;
    }

    /** Appends {@code count} frames with the given header and length, e.g. to mix bitrates (VBR). */
    public SyntheticMp3 moreFrames(int b0, int b1, int b2, int b3, int length, int count) {
        for (int i = 0; i < count; i++) {
            byte[] frame = new byte[length];
            frame[0] = (byte) b0;
            frame[1] = (byte) b1;
            frame[2] = (byte) b2;
            frame[3] = (byte) b3;
            extraFrames.writeBytes(frame);
        }
        return this;
    }

    /** Adds bytes after the audio and before the ID3v1 tag, e.g. an APEv2 tag. */
    public SyntheticMp3 trailingBytes(byte[] bytes) {
        trailingBytes = bytes;
        return this;
    }

    /** Writes the ID3v2 tag as version 2.4 (synchsafe frame sizes) with the footer flag and a 10-byte footer. */
    public SyntheticMp3 id3v2Footer() {
        id3v2Footer = true;
        return this;
    }

    public byte[] build() {
        var out = new ByteArrayOutputStream();
        if (!id3v2Frames.isEmpty()) {
            writeId3v2(out);
        }
        out.writeBytes(junkBeforeAudio);
        for (int i = 0; i < audioFrames; i++) {
            byte[] frame = new byte[FRAME_LENGTH];
            System.arraycopy(frameHeader, 0, frame, 0, frameHeader.length);
            out.writeBytes(frame);
        }
        out.writeBytes(extraFrames.toByteArray());
        out.writeBytes(trailingBytes);
        if (id3v1 != null) {
            out.writeBytes(id3v1Tag());
        }
        return out.toByteArray();
    }

    private void writeId3v2(ByteArrayOutputStream out) {
        byte version = (byte) (id3v2Footer ? 4 : 3);
        byte flags = (byte) (id3v2Footer ? 0x10 : 0);
        var frames = new ByteArrayOutputStream();
        id3v2Frames.forEach((id, content) -> {
            frames.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
            // ID3v2.3 frame sizes are plain 32-bit integers, ID3v2.4 frame sizes are synchsafe
            frames.writeBytes(version == 4 ? synchsafe(content.length) : new byte[]{(byte) (content.length >>> 24),
                    (byte) (content.length >>> 16), (byte) (content.length >>> 8), (byte) content.length});
            frames.writeBytes(new byte[]{0, 0});
            frames.writeBytes(content);
        });
        byte[] synchsafeSize = synchsafe(frames.size());
        out.writeBytes(new byte[]{'I', 'D', '3', version, 0, flags});
        out.writeBytes(synchsafeSize);
        out.writeBytes(frames.toByteArray());
        if (id3v2Footer) {
            out.writeBytes(new byte[]{'3', 'D', 'I', version, 0, flags});
            out.writeBytes(synchsafeSize);
        }
    }

    private byte[] id3v1Tag() {
        byte[] tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        put(tag, 3, 30, id3v1[0]);
        put(tag, 33, 30, id3v1[1]);
        put(tag, 63, 30, id3v1[2]);
        put(tag, 93, 4, id3v1[3]);
        return tag;
    }

    private static byte[] synchsafe(int size) {
        return new byte[]{(byte) ((size >>> 21) & 0x7F), (byte) ((size >>> 14) & 0x7F),
                (byte) ((size >>> 7) & 0x7F), (byte) (size & 0x7F)};
    }

    private static void put(byte[] tag, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, tag, offset, Math.min(length, bytes.length));
    }

    /** An ID3v2 header with the given version and flags, a 4-byte extended header size (the whole tag), then audio. */
    public static byte[] withId3v2Flags(int version, int flags, int extendedHeaderSize, int audioFrames) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', (byte) version, 0, (byte) flags, 0, 0, 0, 4,
                (byte) (extendedHeaderSize >>> 24), (byte) (extendedHeaderSize >>> 16),
                (byte) (extendedHeaderSize >>> 8), (byte) extendedHeaderSize});
        out.writeBytes(builder().audioFrames(audioFrames).build());
        return out.toByteArray();
    }

    /** Reduces the declared ID3v2 tag size, so the tag ends inside its last frame. */
    public static byte[] withId3v2SizeReducedBy(byte[] mp3, int bytes) {
        byte[] copy = Arrays.copyOf(mp3, mp3.length);
        int size = ((copy[6] & 0x7F) << 21) | ((copy[7] & 0x7F) << 14) | ((copy[8] & 0x7F) << 7) | (copy[9] & 0x7F);
        System.arraycopy(synchsafe(size - bytes), 0, copy, 6, 4);
        return copy;
    }

    /** Overwrites every MPEG frame header with zeros, leaving tags and frame sizes intact. */
    static byte[] withCorruptedFrameHeaders(byte[] mp3, int audioStart, int frameCount) {
        byte[] copy = Arrays.copyOf(mp3, mp3.length);
        for (int i = 0; i < frameCount; i++) {
            Arrays.fill(copy, audioStart + i * FRAME_LENGTH, audioStart + i * FRAME_LENGTH + 4, (byte) 0);
        }
        return copy;
    }
}
