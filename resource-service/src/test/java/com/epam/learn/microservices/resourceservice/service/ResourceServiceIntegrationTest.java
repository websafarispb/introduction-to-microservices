package com.epam.learn.microservices.resourceservice.service;

import com.epam.learn.microservices.resourceservice.client.SongServiceClient;
import com.epam.learn.microservices.resourceservice.entity.Resource;
import com.epam.learn.microservices.resourceservice.exception.InvalidIdException;
import com.epam.learn.microservices.resourceservice.exception.ResourceNotFoundException;
import com.epam.learn.microservices.resourceservice.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ResourceService and the resource table against the real PostgreSQL resource-db
 * (start it with {@code docker compose up -d}). No test-managed transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ResourceServiceIntegrationTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SongServiceClient songServiceClient;

    @BeforeEach
    void cleanDatabase() {
        resourceRepository.deleteAllInBatch();
        RecordingStatementInspector.clear();
        // the Song Service deletes every forwarded id (cascade behaviour is tested in ResourceDeleteTransactionTest)
        when(songServiceClient.delete(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Long store(byte[] data) {
        return resourceRepository.save(new Resource(data)).getId();
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    // --- schema and ids ---

    @Test
    void dataColumnIsByteaAndIdIsIdentity() {
        var column = jdbcTemplate.queryForMap("""
                select data_type, is_nullable from information_schema.columns
                where table_schema = 'resource_test' and table_name = 'resource' and column_name = 'data'""");
        var id = jdbcTemplate.queryForMap("""
                select data_type, is_identity from information_schema.columns
                where table_schema = 'resource_test' and table_name = 'resource' and column_name = 'id'""");

        assertThat(column).containsEntry("data_type", "bytea").containsEntry("is_nullable", "NO");
        assertThat(id).containsEntry("data_type", "bigint").containsEntry("is_identity", "YES");
    }

    @Test
    void generatedIdsAreUniqueAndPositive() {
        List<Long> ids = IntStream.range(0, 5).mapToObj(i -> store(new byte[]{(byte) i})).toList();

        assertThat(ids).allSatisfy(id -> assertThat(id).isPositive());
        assertThat(new HashSet<>(ids)).hasSize(5);
    }

    // --- get ---

    @Test
    void storedBytesComeBackIdentical() throws Exception {
        byte[] original = randomBytes(256 * 1024, 42);
        for (int i = 0; i < 256; i++) {
            original[i] = (byte) i; // every possible byte value, including 0x00
        }
        Long id = store(original);

        byte[] loaded = resourceService.get(String.valueOf(id));

        assertThat(loaded).isEqualTo(original);
        assertThat(sha256(loaded)).isEqualTo(sha256(original));
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        assertThatThrownBy(() -> resourceService.get("99999"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Resource with ID=99999 not found");
    }

    @Test
    void getInvalidIdThrowsBeforeQuerying() {
        assertThatThrownBy(() -> resourceService.get("ABC"))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid value 'ABC' for ID. Must be a positive integer");
        assertThat(RecordingStatementInspector.statements()).isEmpty();
    }

    // --- delete ---

    @Test
    void deleteReturnsOnlyExistingIdsInRequestOrderWithoutDuplicates() {
        Long a = store(new byte[]{1});
        Long b = store(new byte[]{2});
        Long kept = store(new byte[]{3});

        List<Long> deleted = resourceService.delete(b + ",99998," + a + "," + b);

        assertThat(deleted).containsExactly(b, a);
        assertThat(resourceRepository.findAll()).extracting(Resource::getId).containsExactly(kept);
    }

    @Test
    void deleteOfUnknownIdsReturnsEmptyListAndRunsNoDelete() {
        assertThat(resourceService.delete("99999")).isEmpty();
        assertThat(RecordingStatementInspector.statements()).noneMatch(sql -> sql.startsWith("delete"));
    }

    @Test
    void deleteNeverLoadsAudioData() {
        Long id = store(randomBytes(1024, 7));
        RecordingStatementInspector.clear();

        resourceService.delete(String.valueOf(id));

        assertThat(RecordingStatementInspector.statements())
                .isNotEmpty()
                .noneMatch(sql -> sql.contains("data"));
    }

    @Test
    void invalidCsvDeletesNothing() {
        Long id = store(new byte[]{1});

        assertThatThrownBy(() -> resourceService.delete(id + ",V"))
                .isInstanceOf(InvalidIdException.class)
                .hasMessage("Invalid ID format: 'V'. Only positive integers are allowed");
        assertThat(resourceRepository.existsById(id)).isTrue();
    }
}
