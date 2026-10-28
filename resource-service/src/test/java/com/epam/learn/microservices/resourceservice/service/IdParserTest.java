package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.exception.InvalidIdException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2: id and CSV parsing with the exact messages of the API response specification.
 */
class IdParserTest {

    private static final String LONG_MAX_PLUS_ONE = "9223372036854775808";

    private final IdParser parser = new IdParser();

    // --- single id (path variable) ---

    @Test
    void parsesPositiveIds() {
        assertThat(parser.parseId("1")).isEqualTo(1L);
        assertThat(parser.parseId("2147483647")).isEqualTo(2147483647L);
        assertThat(parser.parseId(String.valueOf(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void acceptsLeadingZeros() {
        assertThat(parser.parseId("01")).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC", "1.1", "-1", "0", "00", "", " 1", "1 ", "+1", "1e3", "١",
            LONG_MAX_PLUS_ONE, "99999999999999999999"})
    void rejectsInvalidIdWithSpecMessage(String raw) {
        assertThatThrownBy(() -> parser.parseId(raw))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid value '" + raw + "' for ID. Must be a positive integer");
    }

    // --- CSV (query parameter) ---

    @Test
    void parsesCsvKeepingOrderAndRepeats() {
        assertThat(parser.parseCsv("3,1,3")).containsExactly(3L, 1L, 3L);
    }

    @Test
    void acceptsCsvOfExactlyMaximumLength() {
        String csv = "1,".repeat(99) + "11";
        assertThat(csv).hasSize(200);

        assertThat(parser.parseCsv(csv)).hasSize(100);
    }

    @Test
    void rejectsSpecCsvOf208Characters() {
        String csv = "2147483647,2147483646,2147483645,2147483644,2147483643,2147483642,2147483641,2147483640,"
                + "2147483639,2147483638,2147483637,2147483636,2147483635,2147483634,2147483633,2147483632,"
                + "2147483631,2147483630,2147483629";
        assertThat(csv).hasSize(208);

        assertThatThrownBy(() -> parser.parseCsv(csv))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("CSV string is too long: received 208 characters, maximum allowed is 200");
    }

    @Test
    void checksLengthBeforeTokens() {
        String csv = "V".repeat(201);

        assertThatThrownBy(() -> parser.parseCsv(csv))
                .hasMessage("CSV string is too long: received 201 characters, maximum allowed is 200");
    }

    @Test
    void rejectsFirstInvalidTokenWithSpecMessage() {
        assertThatThrownBy(() -> parser.parseCsv("1,2,3,4,V"))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid ID format: 'V'. Only positive integers are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,,2", "1,2,", ",1"})
    void rejectsEmptyTokens(String csv) {
        assertThatThrownBy(() -> parser.parseCsv(csv))
                .hasMessage("Invalid ID format: ''. Only positive integers are allowed");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingOrEmptyParameter(String csv) {
        assertThatThrownBy(() -> parser.parseCsv(csv))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid ID format: ''. Only positive integers are allowed");
    }

    @Test
    void rejectsOverflowingTokenInCsv() {
        assertThatThrownBy(() -> parser.parseCsv("1," + LONG_MAX_PLUS_ONE))
                .hasMessage("Invalid ID format: '" + LONG_MAX_PLUS_ONE + "'. Only positive integers are allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", " 1", "1.5"})
    void rejectsNonPositiveOrMalformedTokens(String token) {
        assertThatThrownBy(() -> parser.parseCsv("1," + token))
                .hasMessage("Invalid ID format: '" + token + "'. Only positive integers are allowed");
    }

    @Test
    void parsesSingleIdCsv() {
        assertThat(parser.parseCsv("99999")).isEqualTo(List.of(99999L));
    }
}
