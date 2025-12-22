package ai.core.memory.store;

import ai.core.memory.longterm.DefaultLongTermMemoryStore;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import ai.core.memory.longterm.store.JdbcMetadataStore;
import ai.core.memory.longterm.store.MilvusMemoryVectorStore;
import ai.core.memory.longterm.store.VectorSearchResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for memory storage backends.
 *
 * <p>These tests require local Docker services:
 * <pre>
 * # PostgreSQL
 * docker run -d --name postgres-test -p 5432:5432 \
 *   -e POSTGRES_USER=test -e POSTGRES_PASSWORD=test -e POSTGRES_DB=memory_test \
 *   postgres:15
 *
 * # Milvus (standalone)
 * docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 \
 *   milvusdb/milvus:latest milvus run standalone
 * </pre>
 *
 * @author xander
 */
@Disabled("Requires local Docker services (PostgreSQL and Milvus)")
@DisplayName("Memory Store Integration Tests")
class MemoryStoreIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryStoreIntegrationTest.class);

    // PostgreSQL connection settings
    private static final String PG_HOST = "localhost";
    private static final int PG_PORT = 5432;
    private static final String PG_DATABASE = "postgres";
    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "postgres";

    // Milvus connection settings
    private static final String MILVUS_HOST = "localhost";
    private static final int MILVUS_PORT = 19530;
    private static final String MILVUS_COLLECTION = "default";
    private static final int EMBEDDING_DIM = 128;

    private static final String USER_ID = "test-user";
    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
    }

    // ==================== Helper Methods ====================

    private MemoryRecord createMemoryRecord(String content, MemoryType type, double importance) {
        return MemoryRecord.builder()
            .namespace(Namespace.forUser(USER_ID))
            .content(content)
            .type(type)
            .importance(importance)
            .build();
    }

    private float[] randomEmbedding() {
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            embedding[i] = random.nextFloat() * 2 - 1;
        }
        return embedding;
    }

    private DataSource createPostgresDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", PG_HOST, PG_PORT, PG_DATABASE));
        config.setUsername(PG_USER);
        config.setPassword(PG_PASSWORD);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    // ==================== PostgreSQL (JdbcMetadataStore) Tests ====================

    @Nested
    @DisplayName("PostgreSQL Metadata Store Tests")
    class PostgresMetadataStoreTests {

        private DataSource dataSource;
        private JdbcMetadataStore store;

        @BeforeEach
        void setUp() {
            dataSource = createPostgresDataSource();
            store = new JdbcMetadataStore(dataSource, LongTermMemoryConfig.MetadataStoreType.POSTGRESQL);
            store.initialize();
            LOGGER.info("PostgreSQL store initialized");
        }

        @AfterEach
        void tearDown() {
            // Clean up test data
            store.deleteByUserId(Namespace.forUser(USER_ID).toPath());
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        }

        @Test
        @DisplayName("Save and retrieve memory record")
        void testSaveAndRetrieve() {
            MemoryRecord record = createMemoryRecord("User prefers dark mode", MemoryType.PREFERENCE, 0.9);

            store.save(record);
            LOGGER.info("Saved record: {}", record.getId());

            var retrieved = store.findById(record.getId());
            assertTrue(retrieved.isPresent(), "Record should be found");
            assertEquals(record.getContent(), retrieved.get().getContent());
            assertEquals(record.getType(), retrieved.get().getType());
            LOGGER.info("Retrieved record: {}", retrieved.get().getContent());
        }

        @Test
        @DisplayName("Save batch of records")
        void testSaveBatch() {
            List<MemoryRecord> records = List.of(
                createMemoryRecord("Fact 1", MemoryType.FACT, 0.8),
                createMemoryRecord("Fact 2", MemoryType.FACT, 0.7),
                createMemoryRecord("Preference 1", MemoryType.PREFERENCE, 0.9)
            );

            store.saveAll(records);
            LOGGER.info("Saved {} records", records.size());

            var found = store.findByUserId(Namespace.forUser(USER_ID).toPath());
            assertEquals(3, found.size(), "Should find all 3 records");
        }

        @Test
        @DisplayName("Update access count")
        void testRecordAccess() {
            MemoryRecord record = createMemoryRecord("Test content", MemoryType.FACT, 0.5);
            store.save(record);

            store.recordAccess(record.getId());
            store.recordAccess(record.getId());

            var retrieved = store.findById(record.getId());
            assertTrue(retrieved.isPresent());
            assertEquals(2, retrieved.get().getAccessCount(), "Access count should be 2");
            LOGGER.info("Access count: {}", retrieved.get().getAccessCount());
        }

        @Test
        @DisplayName("Update decay factor")
        void testUpdateDecayFactor() {
            MemoryRecord record = createMemoryRecord("Test content", MemoryType.FACT, 0.5);
            store.save(record);

            store.updateDecayFactor(record.getId(), 0.75);

            var retrieved = store.findById(record.getId());
            assertTrue(retrieved.isPresent());
            assertEquals(0.75, retrieved.get().getDecayFactor(), 0.001);
            LOGGER.info("Decay factor: {}", retrieved.get().getDecayFactor());
        }

        @Test
        @DisplayName("Find decayed records")
        void testFindDecayed() {
            String namespacePath = Namespace.forUser(USER_ID).toPath();

            // Create records with different decay factors
            var highDecay = createMemoryRecord("High decay", MemoryType.FACT, 0.5);
            var lowDecay = createMemoryRecord("Low decay", MemoryType.FACT, 0.5);

            store.save(highDecay);
            store.save(lowDecay);

            // Update decay factors
            store.updateDecayFactor(highDecay.getId(), 0.9);
            store.updateDecayFactor(lowDecay.getId(), 0.1);

            var decayed = store.findDecayed(namespacePath, 0.5);
            assertEquals(1, decayed.size(), "Should find 1 decayed record");
            assertEquals(lowDecay.getId(), decayed.getFirst().getId());
            LOGGER.info("Found {} decayed records", decayed.size());
        }

        @Test
        @DisplayName("Count records by type")
        void testCountByType() {
            String namespacePath = Namespace.forUser(USER_ID).toPath();

            store.saveAll(List.of(
                createMemoryRecord("Pref 1", MemoryType.PREFERENCE, 0.9),
                createMemoryRecord("Pref 2", MemoryType.PREFERENCE, 0.8),
                createMemoryRecord("Fact 1", MemoryType.FACT, 0.7)
            ));

            int prefCount = store.countByType(namespacePath, MemoryType.PREFERENCE);
            int factCount = store.countByType(namespacePath, MemoryType.FACT);

            assertEquals(2, prefCount, "Should have 2 preferences");
            assertEquals(1, factCount, "Should have 1 fact");
            LOGGER.info("Preferences: {}, Facts: {}", prefCount, factCount);
        }
    }

    // ==================== Milvus (MilvusMemoryVectorStore) Tests ====================

    @Nested
    @DisplayName("Milvus Vector Store Tests")
    class MilvusVectorStoreTests {

        private MilvusMemoryVectorStore store;

        @BeforeEach
        void setUp() {
            // Use unique collection name for each test run
            String collectionName = MILVUS_COLLECTION + "_" + System.currentTimeMillis();
            store = new MilvusMemoryVectorStore(MILVUS_HOST, MILVUS_PORT, collectionName, EMBEDDING_DIM);
            LOGGER.info("Milvus store initialized with collection: {}", collectionName);
        }

        @AfterEach
        void tearDown() {
            try {
                store.dropCollection();
            } catch (Exception e) {
                LOGGER.warn("Failed to drop collection: {}", e.getMessage());
            }
            store.close();
        }

        @Test
        @DisplayName("Save and search vector")
        void testSaveAndSearch() {
            String id = "test-id-1";
            float[] embedding = randomEmbedding();

            store.save(id, embedding);
            LOGGER.info("Saved vector: {}", id);

            // Search with same embedding should return it
            List<VectorSearchResult> results = store.search(embedding, 5);

            assertFalse(results.isEmpty(), "Should find at least 1 result");
            assertEquals(id, results.getFirst().id(), "Should find the saved vector");
            LOGGER.info("Search results: {}", results.size());
        }

        @Test
        @DisplayName("Save batch and search")
        void testSaveBatchAndSearch() {
            List<String> ids = List.of("vec-1", "vec-2", "vec-3");
            List<float[]> embeddings = ids.stream()
                .map(id -> randomEmbedding())
                .toList();

            store.saveAll(ids, embeddings);
            LOGGER.info("Saved {} vectors", ids.size());

            // Search with first embedding
            List<VectorSearchResult> results = store.search(embeddings.getFirst(), 10);

            assertFalse(results.isEmpty());
            assertTrue(results.size() <= 3);
            LOGGER.info("Found {} results", results.size());
        }

        @Test
        @DisplayName("Search with candidate ID filter")
        void testSearchWithFilter() {
            // Save multiple vectors
            List<String> ids = List.of("filter-1", "filter-2", "filter-3", "filter-4");
            List<float[]> embeddings = ids.stream()
                .map(id -> randomEmbedding())
                .toList();

            store.saveAll(ids, embeddings);

            // Search only within first 2 IDs
            List<String> candidateIds = List.of("filter-1", "filter-2");
            List<VectorSearchResult> results = store.search(embeddings.getFirst(), 10, candidateIds);

            assertTrue(results.size() <= 2, "Should only find results from candidate IDs");
            for (var result : results) {
                assertTrue(candidateIds.contains(result.id()),
                    "Result ID should be in candidate list");
            }
            LOGGER.info("Filtered search found {} results", results.size());
        }

        @Test
        @DisplayName("Delete vector")
        void testDeleteVector() {
            String id = "delete-test";
            float[] embedding = randomEmbedding();

            store.save(id, embedding);

            // Verify it exists
            var beforeDelete = store.search(embedding, 5);
            assertTrue(beforeDelete.stream().anyMatch(r -> r.id().equals(id)));

            // Delete and verify
            store.delete(id);

            var afterDelete = store.search(embedding, 5);
            assertFalse(afterDelete.stream().anyMatch(r -> r.id().equals(id)),
                "Deleted vector should not appear in search");
            LOGGER.info("Vector deleted successfully");
        }
    }

    // ==================== Combined Store (PostgreSQL + Milvus) Tests ====================

    @Nested
    @DisplayName("Combined Store Tests (PostgreSQL + Milvus)")
    class CombinedStoreTests {

        private DefaultLongTermMemoryStore store;
        private DataSource dataSource;

        @BeforeEach
        void setUp() {
            dataSource = createPostgresDataSource();
            String collectionName = MILVUS_COLLECTION + "_combined_" + System.currentTimeMillis();

            var config = LongTermMemoryConfig.builder()
                .postgres(dataSource)
                .milvus(MILVUS_HOST, MILVUS_PORT, collectionName)
                .embeddingDimension(EMBEDDING_DIM)
                .build();

            store = DefaultLongTermMemoryStore.create(config);
            LOGGER.info("Combined store initialized");
        }

        @AfterEach
        void tearDown() {
            // Clean up
            try {
                store.deleteByNamespace(Namespace.forUser(USER_ID));
            } catch (Exception e) {
                LOGGER.warn("Cleanup error: {}", e.getMessage());
            }

            // Drop Milvus collection
            if (store.getVectorStore() instanceof MilvusMemoryVectorStore milvusStore) {
                try {
                    milvusStore.dropCollection();
                    milvusStore.close();
                } catch (Exception e) {
                    LOGGER.warn("Milvus cleanup error: {}", e.getMessage());
                }
            }

            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        }

        @Test
        @DisplayName("Save and search memory with both stores")
        void testSaveAndSearch() {
            Namespace namespace = Namespace.forUser(USER_ID);

            // Create memory records with embeddings
            MemoryRecord record1 = createMemoryRecord("User prefers dark mode", MemoryType.PREFERENCE, 0.9);
            MemoryRecord record2 = createMemoryRecord("User is learning Python", MemoryType.FACT, 0.8);

            float[] embedding1 = randomEmbedding();
            float[] embedding2 = randomEmbedding();

            store.save(record1, embedding1);
            store.save(record2, embedding2);

            LOGGER.info("Saved 2 memories to combined store");

            // Search with first embedding
            List<MemoryRecord> results = store.search(namespace, embedding1, 5);

            assertFalse(results.isEmpty(), "Should find memories");
            LOGGER.info("Found {} memories", results.size());

            // Verify metadata is correct
            var foundRecord = results.stream()
                .filter(r -> r.getId().equals(record1.getId()))
                .findFirst();
            assertTrue(foundRecord.isPresent(), "Should find record1");
            assertEquals("User prefers dark mode", foundRecord.get().getContent());
        }

        @Test
        @DisplayName("Delete memory removes from both stores")
        void testDeleteMemory() {
            Namespace namespace = Namespace.forUser(USER_ID);

            MemoryRecord record = createMemoryRecord("Test memory", MemoryType.FACT, 0.5);
            float[] embedding = randomEmbedding();

            store.save(record, embedding);

            // Verify it exists
            assertTrue(store.findById(record.getId()).isPresent());

            // Delete
            store.delete(record.getId());

            // Verify it's gone from metadata store
            assertFalse(store.findById(record.getId()).isPresent());

            // Verify search doesn't return it
            var results = store.search(namespace, embedding, 5);
            assertFalse(results.stream().anyMatch(r -> r.getId().equals(record.getId())));

            LOGGER.info("Memory deleted from both stores");
        }

        @Test
        @DisplayName("Count memories by namespace and type")
        void testCountMemories() {
            Namespace namespace = Namespace.forUser(USER_ID);

            store.save(createMemoryRecord("Pref 1", MemoryType.PREFERENCE, 0.9), randomEmbedding());
            store.save(createMemoryRecord("Pref 2", MemoryType.PREFERENCE, 0.8), randomEmbedding());
            store.save(createMemoryRecord("Fact 1", MemoryType.FACT, 0.7), randomEmbedding());

            int totalCount = store.count(namespace);
            int prefCount = store.countByType(namespace, MemoryType.PREFERENCE);

            assertEquals(3, totalCount, "Should have 3 total memories");
            assertEquals(2, prefCount, "Should have 2 preferences");

            LOGGER.info("Total: {}, Preferences: {}", totalCount, prefCount);
        }
    }
}
