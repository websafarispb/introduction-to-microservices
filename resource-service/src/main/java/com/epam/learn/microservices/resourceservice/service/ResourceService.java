package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.client.SongServiceClient;
import com.epam.learn.microservices.resourceservice.dto.Mp3Metadata;
import com.epam.learn.microservices.resourceservice.dto.SongMetadataRequest;
import com.epam.learn.microservices.resourceservice.entity.Resource;
import com.epam.learn.microservices.resourceservice.exception.InvalidFileFormatException;
import com.epam.learn.microservices.resourceservice.exception.InvalidIdException;
import com.epam.learn.microservices.resourceservice.exception.MetadataDeletionException;
import com.epam.learn.microservices.resourceservice.exception.ResourceNotFoundException;
import com.epam.learn.microservices.resourceservice.exception.SongServiceException;
import com.epam.learn.microservices.resourceservice.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);
    private static final String MP3_MEDIA_TYPE = "audio/mpeg";

    private final ResourceRepository resourceRepository;
    private final IdParser idParser;
    private final Mp3MetadataExtractor metadataExtractor;
    private final SongServiceClient songServiceClient;
    private final TransactionTemplate transactionTemplate;

    public ResourceService(ResourceRepository resourceRepository, IdParser idParser,
            Mp3MetadataExtractor metadataExtractor, SongServiceClient songServiceClient,
            TransactionTemplate transactionTemplate) {
        this.resourceRepository = resourceRepository;
        this.idParser = idParser;
        this.metadataExtractor = metadataExtractor;
        this.songServiceClient = songServiceClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Validates an uploaded MP3 file, stores it and creates its metadata in the Song Service.
     *
     * <p>Validation runs outside the transaction. The INSERT (IDENTITY returns the id immediately) and
     * {@code POST /songs} run in one local transaction, so any Song Service failure rolls the INSERT back.
     * This is not a distributed transaction: if the Song Service gives no response, or the local commit fails
     * after it succeeded, its metadata can remain as an orphan. Such cases are logged with the resource id.
     *
     * @param contentType Content-Type header as received (may be {@code null})
     * @param content     request body (may be {@code null} for an empty body)
     * @throws InvalidFileFormatException if the Content-Type is not audio/mpeg or the content is not MP3 audio
     * @throws SongServiceException       if the Song Service rejects the metadata or cannot be reached
     */
    public Long upload(String contentType, byte[] content) {
        String mediaType = mediaTypeOf(contentType);
        if (!MP3_MEDIA_TYPE.equals(mediaType)) {
            throw new InvalidFileFormatException(
                    "Invalid file format: %s. Only MP3 files are allowed".formatted(mediaType));
        }
        byte[] data = content == null ? new byte[0] : content;
        Mp3Metadata metadata = metadataExtractor.extract(data);

        return transactionTemplate.execute(status -> {
            Long id = resourceRepository.save(new Resource(data)).getId();
            try {
                songServiceClient.create(SongMetadataRequest.of(id, metadata));
            } catch (SongServiceException e) {
                logSongServiceFailure(id, e);
                throw e;
            }
            log.debug("Stored resource {} ({} bytes) with metadata {}", id, data.length, metadata);
            return id;
        });
    }

    private static void logSongServiceFailure(Long resourceId, SongServiceException e) {
        if (e.status() == null) {
            log.error("Song Service call for resource {} failed without a definite answer ({}); resource rolled back. "
                    + "Metadata may exist as an orphan; check GET /songs/{}", resourceId, e.getMessage(), resourceId);
        } else {
            log.warn("Song Service rejected metadata for resource {} with status {}; resource rolled back",
                    resourceId, e.status().value());
        }
    }

    /** "type/subtype" in lower case without parameters; "unknown" if absent; the raw value if unparseable. */
    static String mediaTypeOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "unknown";
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (InvalidMediaTypeException e) {
            return contentType.strip();
        }
    }

    /**
     * @param rawId id as received in the request path
     * @return the stored MP3 bytes
     * @throws InvalidIdException        if the id is not a positive integer
     * @throws ResourceNotFoundException if no resource exists for the id
     */
    @Transactional(readOnly = true)
    public byte[] get(String rawId) {
        Long id = idParser.parseId(rawId);
        return resourceRepository.findById(id)
                .map(Resource::getData)
                .orElseThrow(() -> new ResourceNotFoundException(id));
    }

    /**
     * Deletes the given resources and the song metadata with the same ids, ignoring ids that do not exist.
     *
     * <p>One local transaction: only the ids actually deleted here are forwarded to {@code DELETE /songs}, so
     * standalone metadata is never deleted. Any Song Service failure rolls the local delete back (HTTP 500).
     * If the Song Service gives no response it may still have deleted the metadata; retrying is safe.
     *
     * <p>Known limitation: the existence check takes no lock, so a concurrent delete of the same id can make
     * the response overstate what this request deleted. The final state is still consistent.
     *
     * @param rawCsv comma-separated ids as received in the query parameter (may be {@code null})
     * @return the resource ids deleted, without duplicates, in request order
     * @throws InvalidIdException         if the CSV is too long or contains an invalid id
     * @throws MetadataDeletionException if the Song Service does not confirm the metadata deletion
     */
    @Transactional
    public List<Long> delete(String rawCsv) {
        Set<Long> requested = new LinkedHashSet<>(idParser.parseCsv(rawCsv));
        Set<Long> existing = new HashSet<>(resourceRepository.findExistingIds(requested));
        if (existing.isEmpty()) {
            return List.of();
        }
        resourceRepository.deleteAllByIdInBatch(existing);
        List<Long> deleted = requested.stream()
                .filter(existing::contains)
                .toList();
        deleteMetadata(deleted);
        return deleted;
    }

    private void deleteMetadata(List<Long> resourceIds) {
        List<Long> metadataDeleted;
        try {
            metadataDeleted = songServiceClient.delete(resourceIds);
        } catch (SongServiceException e) {
            if (e.status() == null) {
                log.error("Song Service delete for resources {} failed without a definite answer ({}); local delete "
                        + "rolled back. Metadata may already be deleted; retrying the delete is safe", resourceIds,
                        e.getMessage());
            } else {
                log.error("Song Service rejected delete for resources {} with status {}; local delete rolled back",
                        resourceIds, e.status().value());
            }
            throw new MetadataDeletionException("Song Service did not confirm metadata deletion for resources "
                    + resourceIds);
        }
        List<Long> withoutMetadata = resourceIds.stream().filter(id -> !metadataDeleted.contains(id)).toList();
        if (!withoutMetadata.isEmpty()) {
            log.warn("Deleted resources {} had no song metadata to delete", withoutMetadata);
        }
    }
}
