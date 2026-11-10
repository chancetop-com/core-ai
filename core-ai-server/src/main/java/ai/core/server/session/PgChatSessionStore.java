package ai.core.server.session;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * PostgreSQL chat session store. The JSON document is the entity, while the columns that are filtered
 * or sorted on are kept in sync so session lists never scan and deserialize every row.
 *
 * @author stephen
 */
public class PgChatSessionStore implements ChatSessionStore {
    private static final String TABLE = "app.chat_sessions";
    private static final String SELECT = "SELECT id, user_id, agent_id, source, comparison_id, comparison_pair_id, "
            + "comparison_variant, title, message_count, created_at, last_message_at, notify_on_complete, deleted_at, "
            + "data::text AS data FROM " + TABLE;

    private static ChatSession toSession(PgChatSessionRow row) {
        return JsonUtil.fromJson(ChatSession.class, row.data);
    }

    @Inject
    Database database;

    @Override
    public Optional<ChatSession> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgChatSessionRow.class, id).map(PgChatSessionStore::toSession);
    }

    @Override
    public boolean insertIfAbsent(ChatSession session) {
        return database.execute("INSERT INTO " + TABLE + " (id, user_id, agent_id, source, comparison_id, "
                        + "comparison_pair_id, comparison_variant, title, message_count, created_at, last_message_at, "
                        + "notify_on_complete, deleted_at, data, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now()) ON CONFLICT (id) DO NOTHING",
                session.id, session.userId, session.agentId, session.source, session.comparisonId,
                session.comparisonPairId, session.comparisonVariant, session.title, session.messageCount,
                session.createdAt, session.lastMessageAt, session.notifyOnComplete, session.deletedAt,
                JsonUtil.toJson(session)) > 0;
    }

    @Override
    public void upsert(ChatSession session) {
        database.execute("INSERT INTO " + TABLE + " (id, user_id, agent_id, source, comparison_id, "
                        + "comparison_pair_id, comparison_variant, title, message_count, created_at, last_message_at, "
                        + "notify_on_complete, deleted_at, data, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, agent_id = EXCLUDED.agent_id, "
                        + "source = EXCLUDED.source, comparison_id = EXCLUDED.comparison_id, "
                        + "comparison_pair_id = EXCLUDED.comparison_pair_id, comparison_variant = EXCLUDED.comparison_variant, "
                        + "title = EXCLUDED.title, message_count = EXCLUDED.message_count, created_at = EXCLUDED.created_at, "
                        + "last_message_at = EXCLUDED.last_message_at, notify_on_complete = EXCLUDED.notify_on_complete, "
                        + "deleted_at = EXCLUDED.deleted_at, data = EXCLUDED.data, updated_at = now()",
                session.id, session.userId, session.agentId, session.source, session.comparisonId,
                session.comparisonPairId, session.comparisonVariant, session.title, session.messageCount,
                session.createdAt, session.lastMessageAt, session.notifyOnComplete, session.deletedAt,
                JsonUtil.toJson(session));
    }

    @Override
    public List<ChatSession> find(ChatSessionQuery request) {
        var sql = new StringBuilder(SELECT.length() + 128).append(SELECT);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        var sort = switch (request.sortField) {
            case "created_at", "createdAt" -> "created_at";
            case "last_message_at", "lastMessageAt" -> "last_message_at";
            default -> throw new IllegalArgumentException("unsupported chat session sort field: " + request.sortField);
        };
        var direction = request.ascending ? " ASC NULLS FIRST" : " DESC NULLS LAST";
        var orderBy = " ORDER BY " + sort + direction + " LIMIT ? OFFSET ?";
        sql.append(orderBy);
        params.add(request.limit <= 0 ? 100 : request.limit);
        params.add(Math.max(request.offset, 0));
        return database.select(sql.toString(), PgChatSessionRow.class, params.toArray())
                .stream().map(PgChatSessionStore::toSession).toList();
    }

    @Override
    public long count(ChatSessionQuery request) {
        var sql = new StringBuilder("SELECT count(*) AS total FROM ").append(TABLE);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        return database.selectOne(sql.toString(), PgChatSessionCountRow.class, params.toArray())
                .map(row -> row.total).orElse(0L);
    }

    private void conditions(ChatSessionQuery request, StringBuilder sql, List<Object> params) {
        var where = new ArrayList<String>();
        addIdentityConditions(request, where, params);
        addTimeConditions(request, where, params);
        addArtifactConditions(request, where, params);
        if (!request.includeDeleted) where.add("deleted_at IS NULL");
        if (request.excludeComparisonVariantB) {
            where.add("comparison_variant IS DISTINCT FROM ?");
            params.add("b");
        }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private void addIdentityConditions(ChatSessionQuery request, List<String> where, List<Object> params) {
        if (request.userId != null) {
            where.add("user_id = ?");
            params.add(request.userId);
        }
        addInCondition(where, params, "agent_id", request.agentIds);
        addInCondition(where, params, "id", request.ids);
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) {
            where.add("id NOT IN (" + placeholders(request.excludeIds.size()) + ")");
            params.addAll(request.excludeIds);
        }
        if (request.comparisonId != null) {
            where.add("comparison_id = ?");
            params.add(request.comparisonId);
        }
        if (request.comparisonPairId != null) {
            where.add("comparison_pair_id = ?");
            params.add(request.comparisonPairId);
        }
        if (request.sources != null && !request.sources.isEmpty()) {
            var condition = "source IN (" + placeholders(request.sources.size()) + ")";
            where.add(request.sources.contains("chat") ? "(" + condition + " OR source IS NULL)" : condition);
            params.addAll(request.sources);
        }
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

    private void addTimeConditions(ChatSessionQuery request, List<String> where, List<Object> params) {
        var timeColumn = switch (request.timeField) {
            case "created_at", "createdAt" -> "created_at";
            case "last_message_at", "lastMessageAt" -> "last_message_at";
            default -> throw new IllegalArgumentException("unsupported chat session time field: " + request.timeField);
        };
        if (request.from != null) {
            where.add(timeColumn + " >= ?");
            params.add(request.from);
        }
        if (request.to != null) {
            where.add(timeColumn + " < ?");
            params.add(request.to);
        }
    }

    private void addArtifactConditions(ChatSessionQuery request, List<String> where, List<Object> params) {
        if (request.hasArtifacts != null) {
            where.add(request.hasArtifacts
                    ? "jsonb_array_length(COALESCE(data -> ?, jsonb_build_array())) > 0"
                    : "jsonb_array_length(COALESCE(data -> ?, jsonb_build_array())) = 0");
            params.add("artifacts");
        }
        if (request.artifactFileIds == null || request.artifactFileIds.isEmpty()) return;
        var exists = "EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(data -> ?, jsonb_build_array())) "
                + "artifact WHERE artifact ->> ? IN (" + placeholders(request.artifactFileIds.size()) + "))";
        where.add(exists);
        params.add("artifacts");
        params.add("fileId");
        params.addAll(request.artifactFileIds);
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    @Override
    public void recordComparison(String id, String comparisonId, String comparisonVariant, String comparisonPairId) {
        mutateRequired(id, session -> {
            session.comparisonId = comparisonId;
            session.comparisonVariant = comparisonVariant;
            session.comparisonPairId = comparisonPairId;
        });
    }

    @Override
    public void recordUserMessage(String id, String titleIfAbsent, ZonedDateTime now) {
        mutateRequired(id, session -> {
            if (session.title == null) session.title = titleIfAbsent;
            session.lastMessageAt = now;
            session.messageCount = session.messageCount == null ? 1L : session.messageCount + 1;
        });
    }

    @Override
    public void recordAgentMessage(String id, ZonedDateTime now) {
        mutateRequired(id, session -> {
            session.lastMessageAt = now;
            session.messageCount = session.messageCount == null ? 1L : session.messageCount + 1;
        });
    }

    @Override
    public void addLoadedTools(String id, List<ToolRef> refs) {
        if (refs == null || refs.isEmpty()) return;
        mutateRequired(id, session -> {
            var values = session.loadedTools == null ? new ArrayList<ToolRef>() : new ArrayList<>(session.loadedTools);
            for (var ref : refs) {
                if (values.stream().noneMatch(existing -> sameTool(existing, ref))) values.add(ref);
            }
            session.loadedTools = values;
        });
    }

    private boolean sameTool(ToolRef left, ToolRef right) {
        return left.type == right.type
                && java.util.Objects.equals(left.id, right.id)
                && java.util.Objects.equals(left.source, right.source);
    }

    @Override
    public void addLoadedSkillIds(String id, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return;
        mutateRequired(id, session -> {
            var values = session.loadedSkillIds == null ? new ArrayList<String>() : new ArrayList<>(session.loadedSkillIds);
            for (var skillId : skillIds) if (!values.contains(skillId)) values.add(skillId);
            session.loadedSkillIds = values;
        });
    }

    @Override
    public void addLoadedSubAgentIds(String id, List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return;
        mutateRequired(id, session -> {
            var values = session.loadedSubAgentIds == null ? new ArrayList<String>() : new ArrayList<>(session.loadedSubAgentIds);
            for (var agentId : agentIds) if (!values.contains(agentId)) values.add(agentId);
            session.loadedSubAgentIds = values;
        });
    }

    @Override
    public void removeLoadedSkillIds(String id, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return;
        mutateRequired(id, session -> {
            if (session.loadedSkillIds == null) return;
            var values = new ArrayList<>(session.loadedSkillIds);
            values.removeAll(skillIds);
            session.loadedSkillIds = values;
        });
    }

    @Override
    public void appendArtifact(String id, AgentRunArtifact artifact) {
        mutateRequired(id, session -> {
            var artifacts = session.artifacts == null ? new ArrayList<AgentRunArtifact>() : new ArrayList<>(session.artifacts);
            artifacts.add(artifact);
            session.artifacts = artifacts;
        });
    }

    @Override
    public void updateTitle(String id, String title) {
        mutate(id, session -> session.title = title);
    }

    @Override
    public void updateNotifyOnComplete(String id, boolean enabled) {
        mutate(id, session -> session.notifyOnComplete = enabled);
    }

    @Override
    public boolean softDelete(String id, ZonedDateTime at) {
        return mutate(id, session -> session.deletedAt = at);
    }

    private void mutateRequired(String id, Consumer<ChatSession> mutator) {
        if (!mutate(id, mutator)) {
            throw new IllegalStateException("session registry row missing, sessionId=" + id);
        }
    }

    private boolean mutate(String id, Consumer<ChatSession> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgChatSessionRow.class, id).orElse(null);
            if (row == null) {
                transaction.commit();
                return false;
            }
            var session = toSession(row);
            mutator.accept(session);
            write(session);
            transaction.commit();
            return true;
        }
    }

    private void write(ChatSession session) {
        database.execute("UPDATE " + TABLE + " SET user_id = ?, agent_id = ?, source = ?, comparison_id = ?, "
                        + "comparison_pair_id = ?, comparison_variant = ?, title = ?, message_count = ?, created_at = ?, "
                        + "last_message_at = ?, notify_on_complete = ?, deleted_at = ?, data = ?::jsonb, updated_at = now() "
                        + "WHERE id = ?",
                session.userId, session.agentId, session.source, session.comparisonId, session.comparisonPairId,
                session.comparisonVariant, session.title, session.messageCount, session.createdAt,
                session.lastMessageAt, session.notifyOnComplete, session.deletedAt, JsonUtil.toJson(session), session.id);
    }
}
