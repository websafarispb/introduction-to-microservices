package com.epam.learn.microservices.songservice.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1: SongRequest constraints produce exactly the field messages of the API response specification.
 * Plain Bean Validation, no Spring context and no database.
 */
class SongRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** Field -> message; fails if any field reports more than one message. */
    private static Map<String, String> violations(SongRequest request) {
        return validator.validate(request).stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        ConstraintViolation::getMessage));
    }

    private static SongRequest valid() {
        return new SongRequest(1L, "We are the champions", "Queen", "News of the world", "02:59", "1977");
    }

    @Test
    void validRequestHasNoViolations() {
        assertThat(violations(valid())).isEmpty();
    }

    // --- Cases from the API response specification ---

    @Test
    void invalidDurationAndYear() {
        var request = new SongRequest(102L, "We are the champions", "Queen", "News of the world", "02:77", "01977");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "duration", "Duration must be in mm:ss format with leading zeros",
                "year", "Year must be between 1900 and 2099"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0299", "35"})
    void invalidDurationOnly(String duration) {
        var request = new SongRequest(102L, "We are the champions", "Queen", "News of the world", duration, "1977");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "duration", "Duration must be in mm:ss format with leading zeros"));
    }

    @Test
    void invalidYearOnly() {
        var request = new SongRequest(102L, "We are the champions", "Queen", "News of the world", "02:59", "1");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "year", "Year must be between 1900 and 2099"));
    }

    @Test
    void allFieldsEmptyReportFormatMessages() {
        var request = new SongRequest(102L, "", "", "", "", "");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "duration", "Duration must be in mm:ss format with leading zeros",
                "year", "Year must be between 1900 and 2099",
                "artist", "Artist name must be between 1 and 100 characters",
                "album", "Album name must be between 1 and 100 characters",
                "name", "Song name must be between 1 and 100 characters"));
    }

    @Test
    void missingNameReportsRequired() {
        var request = new SongRequest(103L, null, "Queen", "News of the world", "02:59", "1977");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "name", "Song name is required"));
    }

    @Test
    void allFieldsMissingExceptIdReportRequired() {
        var request = new SongRequest(102L, null, null, null, null, null);
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "duration", "Duration is required",
                "artist", "Artist name is required",
                "year", "Year is required",
                "album", "Album name is required",
                "name", "Song name is required"));
    }

    // --- Boundaries implied by the README rules (not in the spec) ---

    @Test
    void textFieldsAcceptOneToHundredCharacters() {
        var request = new SongRequest(1L, "a", "b".repeat(100), "c", "00:00", "1900");
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void textFieldLongerThanHundredCharactersIsRejected() {
        var request = new SongRequest(1L, "a".repeat(101), "Queen", "News of the world", "02:59", "1977");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "name", "Song name must be between 1 and 100 characters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1900", "1999", "2000", "2099"})
    void yearInsideRangeIsAccepted(String year) {
        var request = new SongRequest(1L, "n", "a", "b", "02:59", year);
        assertThat(violations(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1899", "2100", "197", "19777", "19a7", " 1977"})
    void yearOutsideRangeOrFormatIsRejected(String year) {
        var request = new SongRequest(1L, "n", "a", "b", "02:59", year);
        assertThat(violations(request)).containsOnlyKeys("year");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "00:07", "59:59", "99:59"})
    void durationWithLeadingZerosIsAccepted(String duration) {
        var request = new SongRequest(1L, "n", "a", "b", duration, "1977");
        assertThat(violations(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2:59", "02:5", "02:60", "002:59", "02-59", "ab:cd"})
    void malformedDurationIsRejected(String duration) {
        var request = new SongRequest(1L, "n", "a", "b", duration, "1977");
        assertThat(violations(request)).containsOnlyKeys("duration");
    }

    @Test
    void missingIdIsRejected() {
        var request = new SongRequest(null, "n", "a", "b", "02:59", "1977");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of("id", "Id is required"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveIdIsRejected(long id) {
        var request = new SongRequest(id, "n", "a", "b", "02:59", "1977");
        assertThat(violations(request)).containsExactlyInAnyOrderEntriesOf(Map.of("id", "Id must be a positive number"));
    }
}
