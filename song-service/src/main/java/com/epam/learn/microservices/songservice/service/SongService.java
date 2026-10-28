package com.epam.learn.microservices.songservice.service;

import com.epam.learn.microservices.songservice.dto.SongRequest;
import com.epam.learn.microservices.songservice.dto.SongResponse;
import com.epam.learn.microservices.songservice.entity.Song;
import com.epam.learn.microservices.songservice.exception.InvalidIdException;
import com.epam.learn.microservices.songservice.exception.SongAlreadyExistsException;
import com.epam.learn.microservices.songservice.exception.SongNotFoundException;
import com.epam.learn.microservices.songservice.repository.SongRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SongService {

    /** PostgreSQL SQLState for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** Default name PostgreSQL gives the primary key of table {@code song}. */
    private static final String PRIMARY_KEY_CONSTRAINT = "song_pkey";

    private final SongRepository songRepository;
    private final IdParser idParser;

    public SongService(SongRepository songRepository, IdParser idParser) {
        this.songRepository = songRepository;
        this.idParser = idParser;
    }

    /**
     * Inserts new metadata. The id is the Resource ID; no check against the Resource Service is made.
     *
     * <p>The insert is flushed immediately so that a primary-key conflict surfaces here,
     * inside this method, rather than at commit time. Existing metadata is never overwritten.
     * Only a primary-key conflict is reported as a duplicate; any other integrity violation propagates.
     *
     * @throws SongAlreadyExistsException if metadata with the same id already exists
     */
    @Transactional
    public Long create(SongRequest request) {
        try {
            return songRepository.saveAndFlush(toEntity(request)).getId();
        } catch (DataIntegrityViolationException e) {
            if (isPrimaryKeyConflict(e)) {
                throw new SongAlreadyExistsException(request.id(), e);
            }
            throw e;
        }
    }

    /**
     * @param rawId id as received in the request path
     * @throws InvalidIdException    if the id is not a positive integer
     * @throws SongNotFoundException if no metadata exists for the id
     */
    @Transactional(readOnly = true)
    public SongResponse get(String rawId) {
        Long id = idParser.parseId(rawId);
        return songRepository.findById(id)
                .map(SongService::toResponse)
                .orElseThrow(() -> new SongNotFoundException(id));
    }

    /**
     * Deletes the given ids, ignoring ids that do not exist.
     *
     * <p>Known limitation: existing ids are read first and then deleted. If a concurrent transaction
     * deletes one of them in between, it is still reported as deleted here. Accepted for this
     * assignment; avoiding it would need native {@code DELETE ... RETURNING} or locking.
     *
     * @param rawCsv comma-separated ids as received in the query parameter (may be {@code null})
     * @return the ids that were actually deleted, without duplicates, in request order
     * @throws InvalidIdException if the CSV is too long or contains an invalid id
     */
    @Transactional
    public List<Long> delete(String rawCsv) {
        Set<Long> requested = new LinkedHashSet<>(idParser.parseCsv(rawCsv));
        Set<Long> existing = songRepository.findAllById(requested).stream()
                .map(Song::getId)
                .collect(Collectors.toSet());
        if (existing.isEmpty()) {
            return List.of();
        }
        songRepository.deleteAllByIdInBatch(existing);
        return requested.stream()
                .filter(existing::contains)
                .toList();
    }

    private static boolean isPrimaryKeyConflict(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UNIQUE_VIOLATION.equals(violation.getSQLState())
                        && PRIMARY_KEY_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    private static Song toEntity(SongRequest request) {
        return new Song(request.id(), request.name(), request.artist(), request.album(),
                request.duration(), request.year());
    }

    private static SongResponse toResponse(Song song) {
        return new SongResponse(song.getId(), song.getName(), song.getArtist(), song.getAlbum(),
                song.getDuration(), song.getYear());
    }
}
