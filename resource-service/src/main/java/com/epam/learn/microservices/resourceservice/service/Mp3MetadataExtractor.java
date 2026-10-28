package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.dto.Mp3Metadata;
import com.epam.learn.microservices.resourceservice.exception.InvalidFileFormatException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.mp3.Mp3Parser;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Validates MP3 content and extracts its tags with Apache Tika's {@link Mp3Parser}.
 *
 * <p>Tika does not fail on non-MP3 input: for text, random bytes or an ID3 tag without audio it still
 * reports {@code Content-Type: audio/mpeg}, and a few accidental frame-sync bytes are enough for it to
 * report a sample rate and duration. Validity is therefore decided by {@link Mp3FrameScanner} (chained
 * Layer III frames must cover the audio area); Tika then supplies tags and duration.
 *
 * <p>Known limitation of Tika 3.3.3: ID3v2.4 frame sizes are read as plain integers instead of synchsafe
 * ones, so frames of 128 bytes or more are read too long. If such a frame (e.g. cover art) comes before the
 * title, artist, album or year frame, those tags are lost. ID3v2.3 tags are not affected.
 */
@Component
public class Mp3MetadataExtractor {

    static final String INVALID_MP3_MESSAGE =
            "Invalid file format: content is not a valid MP3. Only MP3 files are allowed";

    public Mp3Metadata extract(byte[] content) {
        if (!Mp3FrameScanner.looksLikeMp3Audio(content)) {
            throw new InvalidFileFormatException(INVALID_MP3_MESSAGE);
        }

        Metadata metadata = new Metadata();
        try {
            new Mp3Parser().parse(new ByteArrayInputStream(content), new DefaultHandler(), metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException | RuntimeException e) {
            // Mp3Parser throws unchecked exceptions on malformed tags (e.g. ArrayIndexOutOfBoundsException);
            // called through Tika's CompositeParser they would be wrapped in a TikaException as well
            throw new InvalidFileFormatException(INVALID_MP3_MESSAGE, e);
        }

        String sampleRate = metadata.get(XMPDM.AUDIO_SAMPLE_RATE);
        String duration = metadata.get(XMPDM.DURATION);
        if (sampleRate == null || duration == null) {
            throw new InvalidFileFormatException(INVALID_MP3_MESSAGE);
        }
        double seconds = Double.parseDouble(duration);
        if (!(seconds > 0)) {
            throw new InvalidFileFormatException(INVALID_MP3_MESSAGE);
        }

        return new Mp3Metadata(
                metadata.get(TikaCoreProperties.TITLE),
                metadata.get(XMPDM.ARTIST),
                metadata.get(XMPDM.ALBUM),
                formatDuration(seconds),
                metadata.get(XMPDM.RELEASE_DATE));
    }

    /** Whole seconds, truncated (7.81 s -> "00:07"); minutes are not capped at 99. */
    static String formatDuration(double seconds) {
        long total = (long) Math.floor(seconds);
        return "%02d:%02d".formatted(total / 60, total % 60);
    }
}
