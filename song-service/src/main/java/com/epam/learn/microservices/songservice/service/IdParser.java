package com.epam.learn.microservices.songservice.service;

import com.epam.learn.microservices.songservice.exception.InvalidIdException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses ids received as raw strings (path variable or CSV query parameter).
 * An id is valid if it consists of ASCII digits only and its value is between 1 and {@link Long#MAX_VALUE}.
 */
@Component
public class IdParser {

    static final int MAX_CSV_LENGTH = 200;

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    public Long parseId(String raw) {
        return toPositiveLong(raw).orElseThrow(() -> new InvalidIdException(
                "Invalid value '%s' for ID. Must be a positive integer".formatted(raw)));
    }

    /**
     * Parses a comma-separated list of ids. The length limit is checked before the individual ids.
     * A missing parameter is treated as an empty string, i.e. one empty (invalid) id.
     */
    public List<Long> parseCsv(String csv) {
        String value = csv == null ? "" : csv;
        if (value.length() > MAX_CSV_LENGTH) {
            throw new InvalidIdException("CSV string is too long: received %d characters, maximum allowed is %d"
                    .formatted(value.length(), MAX_CSV_LENGTH));
        }
        List<Long> ids = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            ids.add(toPositiveLong(token).orElseThrow(() -> new InvalidIdException(
                    "Invalid ID format: '%s'. Only positive integers are allowed".formatted(token))));
        }
        return ids;
    }

    private static Optional<Long> toPositiveLong(String raw) {
        if (raw == null || !DIGITS.matcher(raw).matches()) {
            return Optional.empty();
        }
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty(); // more digits than fit into a long
        }
    }
}
