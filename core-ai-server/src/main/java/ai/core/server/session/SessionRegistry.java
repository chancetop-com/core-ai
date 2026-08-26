package ai.core.server.session;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.util.IdLists;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonArray;
import org.bson.conversions.Bson;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Durable source of truth for session identity and authorization.
 */
public class SessionRegistry {
    private static final int DUPLICATE_KEY_CODE = 11000;
    private static final int TITLE_MAX_LENGTH = 40;
    private static final int MANUAL_TITLE_MAX_LENGTH = 100;

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    public ChatSession create(SessionRegistration registration) {
        var session = new ChatSession();
        session.id = registration.sessionId;
        session.userId = registration.userId;
        session.agentId = registration.agentId;
        session.source = normalizedSource(registration.source);
        session.scheduleId = registration.scheduleId;
        session.apiKeyId = registration.apiKeyId;
        session.messageCount = 0L;
        session.createdAt = ZonedDateTime.now();
        session.loadedTools = new ArrayList<>();
        session.loadedSkillIds = new ArrayList<>();
        session.loadedSubAgentIds = new ArrayList<>();
        session.datasetConfig = registration.datasetConfig;
        try {
            chatSessionCollection.insert(session);
            return session;
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            var existing = chatSessionCollection.get(registration.sessionId).orElse(null);
            if (sameIdentity(existing, registration)) return existing;
            throw new IllegalStateException("session id already registered with different identity, sessionId="
                    + registration.sessionId, e);
        }
    }

    public ChatSession requireAccessible(String sessionId, String callerUserId) {
        var session = requireActive(sessionId);
        if (callerUserId == null || callerUserId.isBlank() || !callerUserId.equals(session.userId)) {
            throw new ForbiddenException("session is unavailable");
        }
        return session;
    }

    public ChatSession get(String sessionId) {
        return chatSessionCollection.get(sessionId).orElse(null);
    }

    public String requireUserId(String sessionId) {
        return requireActive(sessionId).userId;
    }

    public String requireAgentId(String sessionId) {
        return requireActive(sessionId).agentId;
    }

    public void recordUserMessage(String sessionId, String content) {
        var now = ZonedDateTime.now();
        var titled = chatSessionCollection.update(
                Filters.and(
                        Filters.eq("_id", sessionId),
                        Filters.or(Filters.exists("title", false), Filters.eq("title", null))),
                Updates.combine(
                        Updates.set("title", truncateTitle(content)),
                        Updates.set("last_message_at", now),
                        Updates.inc("message_count", 1L)));
        if (titled > 0) return;
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.combine(
                        Updates.set("last_message_at", now),
                        Updates.inc("message_count", 1L))));
    }

    public void addLoadedTools(String sessionId, List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return;
        initializeNullListField(sessionId, "loaded_tools");
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.addEachToSet("loaded_tools", toolRefs)));
    }

    public void recordAgentMessage(String sessionId) {
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.combine(
                        Updates.set("last_message_at", ZonedDateTime.now()),
                        Updates.inc("message_count", 1L))));
    }

    public void addLoadedSkillIds(String sessionId, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return;
        initializeNullListField(sessionId, "loaded_skill_ids");
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.addEachToSet("loaded_skill_ids", cleanSkillIds)));
    }

    public void addLoadedSubAgentIds(String sessionId, List<String> agentIds) {
        var cleanAgentIds = IdLists.clean(agentIds);
        if (cleanAgentIds.isEmpty()) return;
        initializeNullListField(sessionId, "loaded_sub_agent_ids");
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.addEachToSet("loaded_sub_agent_ids", cleanAgentIds)));
    }

    public void removeLoadedSkillIds(String sessionId, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return;
        initializeNullListField(sessionId, "loaded_skill_ids");
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.pullAll("loaded_skill_ids", cleanSkillIds)));
    }

    public boolean softDelete(String userId, String sessionId) {
        var session = chatSessionCollection.get(sessionId).orElse(null);
        if (session == null) return false;
        if (userId != null && !userId.equals(session.userId)) return false;
        return chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.set("deleted_at", ZonedDateTime.now())) > 0;
    }

    public boolean updateTitle(String userId, String sessionId, String title) {
        var session = chatSessionCollection.get(sessionId).orElse(null);
        if (session == null || session.deletedAt != null) return false;
        if (userId != null && !userId.equals(session.userId)) return false;
        var cleaned = normalizeText(title);
        if (cleaned.isEmpty()) return false;
        if (cleaned.length() > MANUAL_TITLE_MAX_LENGTH) cleaned = cleaned.substring(0, MANUAL_TITLE_MAX_LENGTH);
        return chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.set("title", cleaned)) > 0;
    }

    public List<String> batchSoftDelete(String userId, List<String> sessionIds) {
        var deletedAt = ZonedDateTime.now();
        var deletedIds = new ArrayList<String>();
        for (var sessionId : sessionIds) {
            var session = chatSessionCollection.get(sessionId).orElse(null);
            if (session == null || userId != null && !userId.equals(session.userId)) continue;
            var updated = chatSessionCollection.update(
                    Filters.eq("_id", sessionId), Updates.set("deleted_at", deletedAt));
            if (updated > 0) deletedIds.add(sessionId);
        }
        return deletedIds;
    }

    public long countSessions(String userId, List<String> sources, List<String> agentIds) {
        return chatSessionCollection.count(buildSessionFilter(userId, sources, agentIds));
    }

    public List<ChatSession> listSessions(String userId, List<String> sources, List<String> agentIds,
                                          int offset, int limit, String sortField) {
        var query = new Query();
        query.filter = buildSessionFilter(userId, sources, agentIds);
        query.sort = Sorts.descending(sortField);
        query.skip = offset;
        query.limit = limit;
        return chatSessionCollection.find(query);
    }

    private void initializeNullListField(String sessionId, String field) {
        chatSessionCollection.update(
                Filters.and(Filters.eq("_id", sessionId), Filters.eq(field, null)),
                Updates.set(field, new BsonArray()));
    }

    private void requireUpdated(String sessionId, long updated) {
        if (updated == 0) {
            throw new IllegalStateException("session registry row missing, sessionId=" + sessionId);
        }
    }

    private ChatSession requireActive(String sessionId) {
        var session = chatSessionCollection.get(sessionId).orElse(null);
        if (session == null || session.deletedAt != null) {
            throw new NotFoundException("session not found, sessionId=" + sessionId);
        }
        return session;
    }

    private Bson buildSessionFilter(String userId, List<String> sources, List<String> agentIds) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("user_id", userId));
        filters.add(Filters.or(Filters.exists("deleted_at", false), Filters.eq("deleted_at", null)));
        if (sources != null && !sources.isEmpty()) {
            if (sources.contains("chat")) {
                filters.add(Filters.or(
                        Filters.in("source", sources),
                        Filters.exists("source", false),
                        Filters.eq("source", null)));
            } else {
                filters.add(Filters.in("source", sources));
            }
        }
        if (agentIds != null && !agentIds.isEmpty()) filters.add(Filters.in("agent_id", agentIds));
        return Filters.and(filters);
    }

    private boolean sameIdentity(ChatSession existing, SessionRegistration registration) {
        return existing != null
                && Objects.equals(existing.id, registration.sessionId)
                && Objects.equals(existing.userId, registration.userId)
                && Objects.equals(existing.agentId, registration.agentId)
                && Objects.equals(normalizedSource(existing.source), normalizedSource(registration.source))
                && Objects.equals(existing.scheduleId, registration.scheduleId)
                && Objects.equals(existing.apiKeyId, registration.apiKeyId);
    }

    private String normalizedSource(String source) {
        return source == null || source.isBlank() ? "chat" : source;
    }

    private String truncateTitle(String content) {
        var cleaned = normalizeText(content);
        return cleaned.length() > TITLE_MAX_LENGTH ? cleaned.substring(0, TITLE_MAX_LENGTH) : cleaned;
    }

    private String normalizeText(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").trim();
    }

    public record SessionRegistration(String sessionId, String userId, String agentId, String source,
                                      String scheduleId, String apiKeyId, List<AgentDatasetConfig> datasetConfig) { }
}
