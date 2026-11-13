package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL chat message store. Message payloads (thinking, tool calls, events) live in the JSON
 * document; columns carry only the fields that are filtered or sorted on.
 *
 * @author stephen
 */
public class PgChatMessageStore implements ChatMessageStore {
    private static final String TABLE = "app.chat_messages";
    private static final String SELECT = "SELECT id, session_id, seq, role, trace_id, content, created_at, data::text AS data FROM " + TABLE;

    private static ChatMessage toMessage(PgChatMessageRow row) {
        var message = JsonUtil.fromJson(ChatMessage.class, row.data);
        message.content = row.content;
        return message;
    }

    @Inject
    Database database;

    @Override
    public Optional<ChatMessage> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgChatMessageRow.class, id).map(PgChatMessageStore::toMessage);
    }

    @Override
    public boolean insertIfAbsent(ChatMessage message) {
        return database.execute("INSERT INTO " + TABLE + " (id, session_id, seq, role, trace_id, content, created_at, data, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, (?::jsonb - ?), now()) ON CONFLICT DO NOTHING",
                message.id, message.sessionId, message.seq, message.role, message.traceId, sanitizeText(message.content),
                message.createdAt, sanitizeJson(JsonUtil.toJson(message)), "content") > 0;
    }

    /** PostgreSQL text/jsonb cannot contain NUL; tool output occasionally embeds one. */
    private String sanitizeText(String value) {
        return value == null || value.indexOf('\0') < 0 ? value : value.replace("\0", "");
    }

    /**
     * NUL must be removed from the JSON tree, not from the serialized text: a literal "\u0000" in
     * the source is serialized as "\\u0000", and a blind replace would leave a dangling backslash.
     */
    private String sanitizeJson(String json) {
        if (json.indexOf("\\u0000") < 0) return json;
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(json);
            return JsonUtil.OBJECT_MAPPER.writeValueAsString(stripNul(node));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode stripNul(JsonNode node) {
        if (node.isTextual()) {
            var text = node.textValue();
            return text.indexOf('\0') < 0 ? node : TextNode.valueOf(text.replace("\0", ""));
        }
        if (node.isObject()) {
            var object = (ObjectNode) node;
            var fields = new ArrayList<>(object.properties());
            for (var field : fields) object.set(field.getKey(), stripNul(field.getValue()));
            return object;
        }
        if (node.isArray()) {
            var array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) array.set(i, stripNul(array.get(i)));
            return array;
        }
        return node;
    }

    @Override
    public long latestSeq(String sessionId) {
        return database.selectOne("SELECT COALESCE(max(seq), 0) AS seq FROM " + TABLE + " WHERE session_id = ?",
                PgChatMessageSeqRow.class, sessionId).map(row -> row.seq).orElse(0L);
    }

    @Override
    public List<ChatMessage> find(ChatMessageQuery request) {
        var sql = new StringBuilder(SELECT.length() + 128).append(SELECT);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        var sort = switch (request.sortField) {
            case "seq" -> "seq";
            case "created_at", "createdAt" -> "created_at";
            default -> throw new IllegalArgumentException("unsupported chat message sort field: " + request.sortField);
        };
        var direction = request.ascending ? " ASC" : " DESC";
        sql.append(" ORDER BY ").append(sort).append(direction);
        if (request.limit != null && request.limit > 0) {
            sql.append(" LIMIT ?");
            params.add(request.limit);
        }
        sql.append(" OFFSET ?");
        params.add(Math.max(request.offset, 0));
        return database.select(sql.toString(), PgChatMessageRow.class, params.toArray())
                .stream().map(PgChatMessageStore::toMessage).toList();
    }

    @Override
    public long count(ChatMessageQuery request) {
        var sql = new StringBuilder("SELECT count(*) AS total FROM ").append(TABLE);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        return database.selectOne(sql.toString(), PgChatMessageCountRow.class, params.toArray())
                .map(row -> row.total).orElse(0L);
    }

    private void conditions(ChatMessageQuery request, StringBuilder sql, List<Object> params) {
        var where = new ArrayList<String>();
        if (request.anyScope) {
            addAnyScope(request, where, params);
        } else {
            addDirectScope(request, where, params);
        }
        addContentCondition(request, where, params);
        if (request.role != null) {
            where.add("role = ?");
            params.add(request.role);
        }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private void addDirectScope(ChatMessageQuery request, List<String> where, List<Object> params) {
        if (request.sessionId != null) {
            where.add("session_id = ?");
            params.add(request.sessionId);
        }
        addInCondition(where, params, "session_id", request.sessionIds);
        addInCondition(where, params, "trace_id", request.traceIds);
        if (request.from != null) {
            where.add("created_at >= ?");
            params.add(request.from);
        }
        if (request.to != null) {
            where.add("created_at <= ?");
            params.add(request.to);
        }
    }

    private void addAnyScope(ChatMessageQuery request, List<String> where, List<Object> params) {
        var scopes = new ArrayList<String>();
        if (request.traceIds != null && !request.traceIds.isEmpty()) {
            scopes.add("trace_id IN (" + placeholders(request.traceIds.size()) + ")");
            params.addAll(request.traceIds);
        }
        if (request.sessionWindows != null) {
            for (var window : request.sessionWindows) {
                var parts = new ArrayList<String>(3);
                parts.add("session_id = ?");
                params.add(window.sessionId);
                if (window.from != null) {
                    parts.add("created_at >= ?");
                    params.add(window.from);
                }
                if (window.to != null) {
                    parts.add("created_at <= ?");
                    params.add(window.to);
                }
                scopes.add("(" + String.join(" AND ", parts) + ")");
            }
        }
        where.add(scopes.isEmpty() ? "1 = 0" : "(" + String.join(" OR ", scopes) + ")");
    }

    private void addInCondition(List<String> where, List<Object> params, String column, List<String> values) {
        if (values == null) return;
        if (values.isEmpty()) {
            where.add("1 = 0");
        } else if (values.size() == 1) {
            where.add(column + " = ?");
            params.add(values.getFirst());
        } else {
            where.add(column + " IN (" + placeholders(values.size()) + ")");
            params.addAll(values);
        }
    }

    private void addContentCondition(ChatMessageQuery request, List<String> where, List<Object> params) {
        if (request.contentTerms == null || request.contentTerms.isEmpty()) return;
        var parts = new ArrayList<String>(request.contentTerms.size());
        for (var term : request.contentTerms) {
            parts.add("content ILIKE ?");
            params.add("%" + term + "%");
        }
        where.add("(" + String.join(" OR ", parts) + ")");
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
