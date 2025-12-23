package ai.core.memory.history;

import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-based implementation of ChatHistoryStore.
 * Supports PostgreSQL and SQLite backends.
 *
 * @author xander
 */
public class JdbcChatHistoryStore implements ChatHistoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcChatHistoryStore.class);

    private static final String SQLITE_CREATE_SESSION_TABLE = """
        CREATE TABLE IF NOT EXISTS chat_session (
            id TEXT PRIMARY KEY, user_id TEXT NOT NULL, agent_id TEXT,
            title TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, metadata TEXT)
        """;

    private static final String POSTGRES_CREATE_SESSION_TABLE = """
        CREATE TABLE IF NOT EXISTS chat_session (
            id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(255) NOT NULL, agent_id VARCHAR(255),
            title VARCHAR(500), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL, metadata JSONB)
        """;

    private static final String SQLITE_CREATE_MESSAGE_TABLE = """
        CREATE TABLE IF NOT EXISTS chat_message (
            id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, seq_num INTEGER NOT NULL,
            role TEXT NOT NULL, content TEXT, name TEXT, tool_call_id TEXT, tool_calls TEXT,
            created_at TEXT NOT NULL,
            FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE)
        """;

    private static final String POSTGRES_CREATE_MESSAGE_TABLE = """
        CREATE TABLE IF NOT EXISTS chat_message (
            id SERIAL PRIMARY KEY, session_id VARCHAR(36) NOT NULL, seq_num INTEGER NOT NULL,
            role VARCHAR(20) NOT NULL, content TEXT, name VARCHAR(255), tool_call_id VARCHAR(255),
            tool_calls JSONB, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE)
        """;

    private static final String CREATE_SESSION_USER_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_chat_session_user ON chat_session(user_id, updated_at DESC)";
    private static final String CREATE_MESSAGE_SESSION_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message(session_id, seq_num)";

    private static final String INSERT_SESSION =
        "INSERT INTO chat_session (id, user_id, agent_id, title, created_at, updated_at, metadata) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_SESSION =
        "UPDATE chat_session SET title = ?, updated_at = ?, metadata = ? WHERE id = ?";
    private static final String INSERT_MESSAGE =
        "INSERT INTO chat_message (session_id, seq_num, role, content, name, tool_call_id, tool_calls, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_SESSION_BY_ID =
        "SELECT id, user_id, agent_id, title, created_at, updated_at, metadata FROM chat_session WHERE id = ?";
    private static final String SELECT_MESSAGES_BY_SESSION =
        "SELECT role, content, name, tool_call_id, tool_calls FROM chat_message WHERE session_id = ? ORDER BY seq_num";
    private static final String SELECT_SESSIONS_BY_USER =
        "SELECT id, user_id, agent_id, title, created_at, updated_at, metadata FROM chat_session WHERE user_id = ? ORDER BY updated_at DESC";
    private static final String SELECT_SESSIONS_BY_USER_PAGED =
        "SELECT id, user_id, agent_id, title, created_at, updated_at, metadata FROM chat_session WHERE user_id = ? ORDER BY updated_at DESC LIMIT ? OFFSET ?";
    private static final String DELETE_SESSION = "DELETE FROM chat_session WHERE id = ?";
    private static final String DELETE_SESSIONS_BY_USER = "DELETE FROM chat_session WHERE user_id = ?";
    private static final String COUNT_BY_USER = "SELECT COUNT(*) FROM chat_session WHERE user_id = ?";
    private static final String UPDATE_TITLE = "UPDATE chat_session SET title = ?, updated_at = ? WHERE id = ?";
    private static final String GET_MAX_SEQ_NUM = "SELECT COALESCE(MAX(seq_num), 0) FROM chat_message WHERE session_id = ?";
    private static final String UPDATE_SESSION_TIME = "UPDATE chat_session SET updated_at = ? WHERE id = ?";

    private final DataSource dataSource;
    private final DatabaseType databaseType;

    public JdbcChatHistoryStore(DataSource dataSource, DatabaseType databaseType) {
        this.dataSource = dataSource;
        this.databaseType = databaseType;
    }

    public void initialize() {
        String sessionTable = databaseType == DatabaseType.POSTGRESQL
            ? POSTGRES_CREATE_SESSION_TABLE : SQLITE_CREATE_SESSION_TABLE;
        String messageTable = databaseType == DatabaseType.POSTGRESQL
            ? POSTGRES_CREATE_MESSAGE_TABLE : SQLITE_CREATE_MESSAGE_TABLE;
        executeUpdate(sessionTable);
        executeUpdate(messageTable);
        executeUpdate(CREATE_SESSION_USER_INDEX);
        executeUpdate(CREATE_MESSAGE_SESSION_INDEX);
        LOGGER.info("Chat history tables initialized");
    }

    @Override
    public void save(ChatSession session) {
        boolean exists = findById(session.getId()).isPresent();
        if (exists) {
            updateSession(session);
        } else {
            insertSession(session);
        }
        if (session.getMessages() != null && !session.getMessages().isEmpty()) {
            int startSeq = getMaxSeqNum(session.getId()) + 1;
            insertMessages(session.getId(), session.getMessages(), startSeq);
        }
    }

    @Override
    public void appendMessages(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int startSeq = getMaxSeqNum(sessionId) + 1;
        insertMessages(sessionId, messages, startSeq);
        updateSessionTime(sessionId);
    }

    @Override
    public Optional<ChatSession> findById(String sessionId) {
        ChatSession session = querySession(sessionId);
        if (session != null) {
            session.setMessages(getMessages(sessionId));
            return Optional.of(session);
        }
        return Optional.empty();
    }

    @Override
    public List<Message> getMessages(String sessionId) {
        return queryMessages(sessionId);
    }

    @Override
    public List<ChatSession> listByUser(String userId) {
        return querySessionsByUser(userId, -1, 0);
    }

    @Override
    public List<ChatSession> listByUser(String userId, int limit, int offset) {
        return querySessionsByUser(userId, limit, offset);
    }

    @Override
    public void delete(String sessionId) {
        executeUpdate(DELETE_SESSION, sessionId);
    }

    @Override
    public void deleteByUser(String userId) {
        int deleted = executeUpdate(DELETE_SESSIONS_BY_USER, userId);
        LOGGER.info("Deleted {} sessions for user {}", deleted, userId);
    }

    @Override
    public int countByUser(String userId) {
        return queryCount(userId);
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        executeUpdateTitle(sessionId, title);
    }

    // ==================== Private Helper Methods ====================

    private void executeUpdate(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute SQL: " + sql, e);
        }
    }

    private int executeUpdate(String sql, String param) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, param);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute SQL", e);
        }
    }

    private void insertSession(ChatSession session) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SESSION)) {
            stmt.setString(1, session.getId());
            stmt.setString(2, session.getUserId());
            stmt.setString(3, session.getAgentId());
            stmt.setString(4, session.getTitle());
            setTimestamp(stmt, 5, session.getCreatedAt());
            setTimestamp(stmt, 6, session.getUpdatedAt());
            setJson(stmt, 7, session.getMetadata());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert session", e);
        }
    }

    private void updateSession(ChatSession session) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SESSION)) {
            stmt.setString(1, session.getTitle());
            setTimestamp(stmt, 2, session.getUpdatedAt());
            setJson(stmt, 3, session.getMetadata());
            stmt.setString(4, session.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update session", e);
        }
    }

    private void updateSessionTime(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SESSION_TIME)) {
            setTimestamp(stmt, 1, Instant.now());
            stmt.setString(2, sessionId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update session time", e);
        }
    }

    private void executeUpdateTitle(String sessionId, String title) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_TITLE)) {
            stmt.setString(1, title);
            setTimestamp(stmt, 2, Instant.now());
            stmt.setString(3, sessionId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update title", e);
        }
    }

    private void insertMessages(String sessionId, List<Message> messages, int startSeq) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_MESSAGE)) {
            int seq = startSeq;
            for (Message msg : messages) {
                stmt.setString(1, sessionId);
                stmt.setInt(2, seq++);
                stmt.setString(3, msg.role != null ? msg.role.name() : "USER");
                stmt.setString(4, msg.content);
                stmt.setString(5, msg.name);
                stmt.setString(6, msg.toolCallId);
                setJson(stmt, 7, msg.toolCalls);
                setTimestamp(stmt, 8, Instant.now());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert messages", e);
        }
    }

    private int getMaxSeqNum(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_MAX_SEQ_NUM)) {
            stmt.setString(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get max seq num", e);
        }
        return 0;
    }

    private ChatSession querySession(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_SESSION_BY_ID)) {
            stmt.setString(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapSession(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query session", e);
        }
        return null;
    }

    private List<Message> queryMessages(String sessionId) {
        List<Message> messages = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_MESSAGES_BY_SESSION)) {
            stmt.setString(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query messages", e);
        }
        return messages;
    }

    private List<ChatSession> querySessionsByUser(String userId, int limit, int offset) {
        List<ChatSession> sessions = new ArrayList<>();
        String sql = limit > 0 ? SELECT_SESSIONS_BY_USER_PAGED : SELECT_SESSIONS_BY_USER;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            if (limit > 0) {
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query sessions by user", e);
        }
        return sessions;
    }

    private int queryCount(String param) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_BY_USER)) {
            stmt.setString(1, param);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query count", e);
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private ChatSession mapSession(ResultSet rs) throws SQLException {
        ChatSession session = new ChatSession();
        session.setId(rs.getString("id"));
        session.setUserId(rs.getString("user_id"));
        session.setAgentId(rs.getString("agent_id"));
        session.setTitle(rs.getString("title"));
        session.setCreatedAt(getTimestamp(rs, "created_at"));
        session.setUpdatedAt(getTimestamp(rs, "updated_at"));
        String metadataJson = rs.getString("metadata");
        if (metadataJson != null && !metadataJson.isBlank()) {
            session.setMetadata(JSON.fromJSON(Map.class, metadataJson));
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private Message mapMessage(ResultSet rs) throws SQLException {
        String roleStr = rs.getString("role");
        RoleType role = roleStr != null ? RoleType.valueOf(roleStr) : RoleType.USER;
        String content = rs.getString("content");
        String name = rs.getString("name");
        String toolCallId = rs.getString("tool_call_id");
        List<FunctionCall> toolCalls = null;
        String toolCallsJson = rs.getString("tool_calls");
        if (toolCallsJson != null && !toolCallsJson.isBlank()) {
            toolCalls = JSON.fromJSON(List.class, toolCallsJson);
        }
        return Message.of(role, content, name, toolCallId, null, toolCalls);
    }

    private void setTimestamp(PreparedStatement stmt, int index, Instant instant) throws SQLException {
        Instant value = instant != null ? instant : Instant.now();
        if (databaseType == DatabaseType.POSTGRESQL) {
            stmt.setTimestamp(index, Timestamp.from(value));
        } else {
            stmt.setString(index, value.toString());
        }
    }

    private Instant getTimestamp(ResultSet rs, String column) throws SQLException {
        if (databaseType == DatabaseType.POSTGRESQL) {
            Timestamp ts = rs.getTimestamp(column);
            return ts != null ? ts.toInstant() : Instant.now();
        }
        String str = rs.getString(column);
        return str != null ? Instant.parse(str) : Instant.now();
    }

    private void setJson(PreparedStatement stmt, int index, Object obj) throws SQLException {
        if (obj == null) {
            stmt.setNull(index, databaseType == DatabaseType.POSTGRESQL ? Types.OTHER : Types.VARCHAR);
            return;
        }
        String json = JSON.toJSON(obj);
        if (databaseType == DatabaseType.POSTGRESQL) {
            stmt.setObject(index, json, Types.OTHER);
        } else {
            stmt.setString(index, json);
        }
    }

    public enum DatabaseType {
        POSTGRESQL, SQLITE
    }
}
