package com.epam.learn.microservices.songservice.service;

import com.epam.learn.microservices.songservice.dto.SongRequest;
import com.epam.learn.microservices.songservice.dto.SongResponse;
import com.epam.learn.microservices.songservice.exception.InvalidIdException;
import com.epam.learn.microservices.songservice.exception.SongAlreadyExistsException;
import com.epam.learn.microservices.songservice.exception.SongNotFoundException;
import com.epam.learn.microservices.songservice.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SongService against the real PostgreSQL song-db (start it with {@code docker compose up -d}).
 * No test-managed transaction: every service call commits or rolls back exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SongServiceIntegrationTest {

    @Autowired
    private SongService songService;

    @Autowired
    private SongRepository songRepository;

    @BeforeEach
    void cleanDatabase() {
        songRepository.deleteAllInBatch();
        RecordingStatementInspector.clear();
    }

    private static SongRequest song(long id, String name) {
        return new SongRequest(id, name, "Queen", "News of the world", "02:59", "1977");
    }

    private static List<String> statementsStartingWith(List<String> statements, String prefix) {
        return statements.stream()
                .filter(sql -> sql.startsWith(prefix))
                .toList();
    }

    // --- create ---

    @Test
    void createInsertsWithoutSelectOrUpdate() {
        Long id = songService.create(song(1, "We are the champions"));
        List<String> sql = RecordingStatementInspector.statements();

        assertThat(id).isEqualTo(1L);
        assertThat(statementsStartingWith(sql, "insert into song_test.song")).hasSize(1);
        assertThat(statementsStartingWith(sql, "update")).isEmpty();
        assertThat(statementsStartingWith(sql, "select")).isEmpty();
        assertThat(songRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicateIdIsRejectedAndOriginalIsKept() {
        songService.create(song(1, "Original"));
        SongResponse original = songService.get("1");
        RecordingStatementInspector.clear();

        var overwriteAttempt = new SongRequest(1L, "Overwrite attempt", "Other artist", "Other album", "09:09", "2001");
        assertThatThrownBy(() -> songService.create(overwriteAttempt))
                .isInstanceOf(SongAlreadyExistsException.class)
                .hasMessage("Metadata for resource ID=1 already exists")
                .hasCauseInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(e -> assertThat(hibernateViolation(e)).satisfies(v -> {
                    assertThat(v.getSQLState()).isEqualTo("23505");
                    assertThat(v.getConstraintName()).isEqualTo("song_pkey");
                }));
        List<String> sql = RecordingStatementInspector.statements();

        assertThat(statementsStartingWith(sql, "insert into song_test.song")).hasSize(1);
        assertThat(statementsStartingWith(sql, "update")).isEmpty();
        assertThat(statementsStartingWith(sql, "select")).isEmpty();
        assertThat(songRepository.count()).isEqualTo(1);
        assertThat(songService.get("1")).isEqualTo(original);
    }

    @Test
    void unrelatedIntegrityViolationIsNotReportedAsDuplicate() {
        // Bypasses Bean Validation on purpose: the NOT NULL column constraint is violated (SQLState 23502).
        var missingName = new SongRequest(9L, null, "Queen", "News of the world", "02:59", "1977");

        assertThatThrownBy(() -> songService.create(missingName))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(SongAlreadyExistsException.class)
                .satisfies(e -> assertThat(hibernateViolation(e).getSQLState()).isEqualTo("23502"));
        assertThat(songRepository.count()).isZero();
    }

    private static org.hibernate.exception.ConstraintViolationException hibernateViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return violation;
            }
        }
        throw new AssertionError("No Hibernate ConstraintViolationException in cause chain of " + e);
    }

    @Test
    void failedDuplicateInsertRollsBackCleanlyAndServiceStaysUsable() {
        songService.create(song(1, "Original"));
        assertThatThrownBy(() -> songService.create(song(1, "Duplicate")))
                .isInstanceOf(SongAlreadyExistsException.class);

        Long next = songService.create(song(2, "Next song"));

        assertThat(next).isEqualTo(2L);
        assertThat(songRepository.findAll()).extracting("id").containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void standaloneMetadataWithoutResourceIsAccepted() {
        Long id = songService.create(song(654321, "Standalone"));

        assertThat(songService.get(String.valueOf(id)).name()).isEqualTo("Standalone");
    }

    // --- get ---

    @Test
    void getReturnsAllStoredFields() {
        songService.create(new SongRequest(7L, "Test Title", "Test Artist", "Test Album", "00:07", "2025"));

        assertThat(songService.get("7"))
                .isEqualTo(new SongResponse(7L, "Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        assertThatThrownBy(() -> songService.get("99999"))
                .isInstanceOf(SongNotFoundException.class)
                .hasMessage("Song metadata for ID=99999 not found");
    }

    @Test
    void invalidIdsAreRejectedBeforeAnyDatabaseChange() {
        songService.create(song(1, "Kept"));

        assertThatThrownBy(() -> songService.get("ABC"))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid value 'ABC' for ID. Must be a positive integer");
        assertThatThrownBy(() -> songService.delete("1,V"))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid ID format: 'V'. Only positive integers are allowed");
        assertThat(songRepository.count()).isEqualTo(1);
    }

    // --- delete ---

    @Test
    void deleteReturnsOnlyIdsThatExisted() {
        songService.create(song(1, "One"));
        songService.create(song(2, "Two"));

        List<Long> deleted = songService.delete("1,101,2,102");

        assertThat(deleted).containsExactly(1L, 2L);
        assertThat(songRepository.count()).isZero();
    }

    @Test
    void deleteKeepsRequestOrder() {
        songService.create(song(1, "One"));
        songService.create(song(3, "Three"));

        assertThat(songService.delete("3,1")).containsExactly(3L, 1L);
    }

    @Test
    void deleteWithRepeatedIdsReportsEachIdOnce() {
        songService.create(song(5, "Five"));

        assertThat(songService.delete("5,5,5")).containsExactly(5L);
        assertThat(songService.delete("5,5")).isEmpty();
    }

    @Test
    void deleteOfUnknownIdsReturnsEmptyListAndRunsNoDelete() {
        assertThat(songService.delete("99999")).isEmpty();
        assertThat(statementsStartingWith(RecordingStatementInspector.statements(), "delete")).isEmpty();
    }

    @Test
    void deleteLeavesOtherRecordsUntouched() {
        songService.create(song(1, "To delete"));
        songService.create(song(654321, "Standalone"));

        songService.delete("1");

        assertThat(songService.get("654321").name()).isEqualTo("Standalone");
        assertThatThrownBy(() -> songService.get("1")).isInstanceOf(SongNotFoundException.class);
    }
}
