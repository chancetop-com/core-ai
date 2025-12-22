package ai.core.memory.longterm.store;

import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.Namespace;
import ai.core.memory.longterm.SearchFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-based implementation of MemoryMetadataStore.
 * Supports SQLite and PostgreSQL through standard JDBC.
 *
 * @author xander
 */
public class JdbcMetadataStore implements MemoryMetadataStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcMetadataStore.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UPSERT_SQL = """
        INSERT INTO memory_record (id, namespace_path, content, type, importance, access_count,
            decay_factor, session_id, created_at, last_accessed_at, metadata)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET namespace_path = excluded.namespace_path,
            content = excluded.content, type = excluded.type, importance = excluded.importance,
            access_count = excluded.access_count, decay_factor = excluded.decay_factor,
            session_id = excluded.session_id, last_accessed_at = excluded.last_accessed_at,
            metadata = excluded.metadata
        """;
    private static final String SQLITE_CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS memory_record (
            id TEXT PRIMARY KEY, namespace_path TEXT NOT NULL, content TEXT NOT NULL,
            type TEXT NOT NULL, importance REAL NOT NULL DEFAULT 0.5,
            access_count INTEGER NOT NULL DEFAULT 0, decay_factor REAL NOT NULL DEFAULT 1.0,
            session_id TEXT, created_at TEXT NOT NULL, last_accessed_at TEXT NOT NULL, metadata TEXT)
        """;
    private static final String POSTGRES_CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS memory_record (
            id VARCHAR(36) PRIMARY KEY, namespace_path VARCHAR(255) NOT NULL, content TEXT NOT NULL,
            type VARCHAR(20) NOT NULL, importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
            access_count INT NOT NULL DEFAULT 0, decay_factor DOUBLE PRECISION NOT NULL DEFAULT 1.0,
            session_id VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            last_accessed_at TIMESTAMP WITH TIME ZONE NOT NULL, metadata JSONB)
        """;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;
    private final LongTermMemoryConfig.MetadataStoreType storeType;

    public JdbcMetadataStore(DataSource dataSource, LongTermMemoryConfig.MetadataStoreType storeType) {
        this.dataSource = dataSource;
        this.storeType = storeType;
    }

    /**
     * Initialize the database schema. Call this after creating the store.
     */
    public void initialize() {
        String createTableSql = switch (storeType) {
            case SQLITE -> SQLITE_CREATE_TABLE;
            case POSTGRESQL -> POSTGRES_CREATE_TABLE;
            case IN_MEMORY -> throw new IllegalStateException("Use InMemoryMetadataStore for IN_MEMORY type");
        };
        String[] indexSqls = storeType == LongTermMemoryConfig.MetadataStoreType.SQLITE
            ? new String[]{"CREATE INDEX IF NOT EXISTS idx_namespace ON memory_record(namespace_path)",
                           "CREATE INDEX IF NOT EXISTS idx_type ON memory_record(type)",
                           "CREATE INDEX IF NOT EXISTS idx_decay ON memory_record(decay_factor)"}
            : new String[]{"CREATE INDEX IF NOT EXISTS idx_memory_namespace ON memory_record(namespace_path)",
                           "CREATE INDEX IF NOT EXISTS idx_memory_type ON memory_record(type)",
                           "CREATE INDEX IF NOT EXISTS idx_memory_decay ON memory_record(decay_factor)"};
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(createTableSql);
            for (String sql : indexSqls) {
                conn.createStatement().execute(sql);
            }
            LOGGER.info("Initialized memory_record table for {}", storeType);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    @Override
    public void save(MemoryRecord record) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            setRecordParams(stmt, record);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save memory record: " + record.getId(), e);
        }
    }

    @Override
    public void saveAll(List<MemoryRecord> records) {
        if (records.isEmpty()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            conn.setAutoCommit(false);
            for (MemoryRecord record : records) {
                setRecordParams(stmt, record);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save memory records batch", e);
        }
    }

    private void setRecordParams(PreparedStatement stmt, MemoryRecord record) throws SQLException {
        stmt.setString(1, record.getId());
        stmt.setString(2, record.getNamespace() != null ? record.getNamespace().toPath() : "");
        stmt.setString(3, record.getContent());
        stmt.setString(4, record.getType() != null ? record.getType().name() : null);
        stmt.setDouble(5, record.getImportance());
        stmt.setInt(6, record.getAccessCount());
        stmt.setDouble(7, record.getDecayFactor());
        stmt.setString(8, record.getSessionId());
        stmt.setString(9, formatTimestamp(record.getCreatedAt()));
        stmt.setString(10, formatTimestamp(record.getLastAccessedAt()));
        stmt.setString(11, serializeMetadata(record.getMetadata()));
    }

    @Override
    public Optional<MemoryRecord> findById(String id) {
        String sql = "SELECT * FROM memory_record WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapResultSet(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find memory record: " + id, e);
        }
        return Optional.empty();
    }

    @Override
    public List<MemoryRecord> findByIds(List<String> ids) {
        if (ids.isEmpty()) return List.of();

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "SELECT * FROM memory_record WHERE id IN (" + placeholders + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 1, ids.get(i));
            }
            return executeQuery(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find memory records by ids", e);
        }
    }

    @Override
    public List<MemoryRecord> findByUserId(String userId) {
        String sql = "SELECT * FROM memory_record WHERE namespace_path = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            return executeQuery(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find memory records by userId: " + userId, e);
        }
    }

    @Override
    public void delete(String id) {
        String sql = "DELETE FROM memory_record WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete memory record: " + id, e);
        }
    }

    @Override
    public void deleteByUserId(String userId) {
        String sql = "DELETE FROM memory_record WHERE namespace_path = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete memory records for userId: " + userId, e);
        }
    }

    @Override
    public List<MemoryRecord> findByUserIdWithFilter(String userId, SearchFilter filter) {
        List<MemoryRecord> records = findByUserId(userId);
        if (filter == null) return records;
        return records.stream().filter(filter::matches).toList();
    }

    @Override
    public List<MemoryRecord> findDecayed(String userId, double threshold) {
        String sql = "SELECT * FROM memory_record WHERE namespace_path = ? AND decay_factor < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setDouble(2, threshold);
            return executeQuery(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find decayed records for userId: " + userId, e);
        }
    }

    @Override
    public List<MemoryRecord> findAllDecayed(double threshold) {
        String sql = "SELECT * FROM memory_record WHERE decay_factor < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, threshold);
            return executeQuery(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all decayed records", e);
        }
    }

    @Override
    public List<MemoryRecord> findAll() {
        String sql = "SELECT * FROM memory_record";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            return executeQuery(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all memory records", e);
        }
    }

    @Override
    public void recordAccess(String id) {
        String sql = """
            UPDATE memory_record
            SET access_count = access_count + 1, last_accessed_at = ?
            WHERE id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, formatTimestamp(Instant.now()));
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record access for: " + id, e);
        }
    }

    @Override
    public void recordAccess(List<String> ids) {
        if (ids.isEmpty()) return;

        String sql = """
            UPDATE memory_record
            SET access_count = access_count + 1, last_accessed_at = ?
            WHERE id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            String now = formatTimestamp(Instant.now());
            for (String id : ids) {
                stmt.setString(1, now);
                stmt.setString(2, id);
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record access for multiple ids", e);
        }
    }

    @Override
    public void updateDecayFactor(String id, double decayFactor) {
        String sql = "UPDATE memory_record SET decay_factor = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, decayFactor);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update decay factor for: " + id, e);
        }
    }

    @Override
    public void updateDecayFactors(List<String> ids, List<Double> decayFactors) {
        if (ids.isEmpty()) return;
        if (ids.size() != decayFactors.size()) {
            throw new IllegalArgumentException("ids and decayFactors must have same size");
        }

        String sql = "UPDATE memory_record SET decay_factor = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setDouble(1, decayFactors.get(i));
                stmt.setString(2, ids.get(i));
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update decay factors batch", e);
        }
    }

    @Override
    public int count(String userId) {
        String sql = "SELECT COUNT(*) FROM memory_record WHERE namespace_path = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count records for userId: " + userId, e);
        }
    }

    @Override
    public int countByType(String userId, MemoryType type) {
        String sql = "SELECT COUNT(*) FROM memory_record WHERE namespace_path = ? AND type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, type.name());
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count records by type for userId: " + userId, e);
        }
    }

    @Override
    public int countAll() {
        String sql = "SELECT COUNT(*) FROM memory_record";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count all records", e);
        }
    }

    private List<MemoryRecord> executeQuery(PreparedStatement stmt) throws SQLException {
        List<MemoryRecord> results = new ArrayList<>();
        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            results.add(mapResultSet(rs));
        }
        return results;
    }

    private MemoryRecord mapResultSet(ResultSet rs) throws SQLException {
        MemoryRecord record = new MemoryRecord();
        record.setId(rs.getString("id"));

        String namespacePath = rs.getString("namespace_path");
        if (namespacePath != null && !namespacePath.isEmpty()) {
            record.setNamespace(Namespace.fromPath(namespacePath));
        }

        record.setContent(rs.getString("content"));

        String typeName = rs.getString("type");
        if (typeName != null) {
            try {
                record.setType(MemoryType.valueOf(typeName));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown memory type: {}", typeName);
            }
        }

        record.setImportance(rs.getDouble("importance"));
        record.setAccessCount(rs.getInt("access_count"));
        record.setDecayFactor(rs.getDouble("decay_factor"));
        record.setSessionId(rs.getString("session_id"));
        record.setCreatedAt(parseTimestamp(rs.getString("created_at")));
        record.setLastAccessedAt(parseTimestamp(rs.getString("last_accessed_at")));

        String metadataJson = rs.getString("metadata");
        if (metadataJson != null && !metadataJson.isEmpty()) {
            record.setMetadata(deserializeMetadata(metadataJson));
        }

        return record;
    }

    private String formatTimestamp(Instant instant) {
        return instant != null ? instant.toString() : Instant.now().toString();
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return Instant.now();
        }
        // Try ISO-8601 format first, then JDBC timestamp format
        Instant result = parseAsIsoInstant(timestamp);
        if (result != null) return result;
        result = parseAsJdbcTimestamp(timestamp);
        return result != null ? result : Instant.now();
    }

    private Instant parseAsIsoInstant(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant parseAsJdbcTimestamp(String timestamp) {
        try {
            return Timestamp.valueOf(timestamp).toInstant();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse timestamp: {}", timestamp);
            return null;
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize metadata", e);
            return null;
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to deserialize metadata: {}", json, e);
            return new HashMap<>();
        }
    }
}
