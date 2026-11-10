package ai.core.server.session;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.util.IdLists;
import core.framework.inject.Inject;
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
    private static final int TITLE_MAX_LENGTH = 40;
    private static final int MANUAL_TITLE_MAX_LENGTH = 100;

    @Inject
    ChatSessionStore chatSessionStore;

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
        if (chatSessionStore.insertIfAbsent(session)) return session;
        var existing = chatSessionStore.get(registration.sessionId).orElse(null);
        if (sameIdentity(existing, registration)) return existing;
        throw new IllegalStateException("session id already registered with different identity, sessionId="
                + registration.sessionId);
    }

    public ChatSession requireAccessible(String sessionId, String callerUserId) {
        var session = requireActive(sessionId);
        if (callerUserId == null || callerUserId.isBlank() || !callerUserId.equals(session.userId)) {
            throw new ForbiddenException("session is unavailable");
        }
        return session;
    }

    public ChatSession get(String sessionId) {
        return chatSessionStore.get(sessionId).orElse(null);
    }

    /** Both rows of one comparison session pair (variant "a" and "b"), keyed by their shared pair id. */
    public List<ChatSession> findByComparisonPairId(String pairId) {
        if (pairId == null) return List.of();
        var query = new ChatSessionQuery();
        query.comparisonPairId = pairId;
        return chatSessionStore.find(query);
    }

    /**
     * Stamps a freshly created session as a member of a comparison pair. Called before the session ids
     * are returned to the client, so nothing else can reach the session and rebuild it from the live
     * agent definition in between; a crash in this window falls back to the normal rebuild path.
     */
    public void recordComparison(String sessionId, String comparisonId, String comparisonVariant, String comparisonPairId) {
        chatSessionStore.recordComparison(sessionId, comparisonId, comparisonVariant, comparisonPairId);
    }

    public String requireUserId(String sessionId) {
        return requireActive(sessionId).userId;
    }

    public String requireAgentId(String sessionId) {
        return requireActive(sessionId).agentId;
    }

    public void recordUserMessage(String sessionId, String content) {
        chatSessionStore.recordUserMessage(sessionId, truncateTitle(content), ZonedDateTime.now());
    }

    public void addLoadedTools(String sessionId, List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return;
        chatSessionStore.addLoadedTools(sessionId, toolRefs);
    }

    public void recordAgentMessage(String sessionId) {
        chatSessionStore.recordAgentMessage(sessionId, ZonedDateTime.now());
    }

    public void addLoadedSkillIds(String sessionId, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return;
        chatSessionStore.addLoadedSkillIds(sessionId, cleanSkillIds);
    }

    public void addLoadedSubAgentIds(String sessionId, List<String> agentIds) {
        var cleanAgentIds = IdLists.clean(agentIds);
        if (cleanAgentIds.isEmpty()) return;
        chatSessionStore.addLoadedSubAgentIds(sessionId, cleanAgentIds);
    }

    public void removeLoadedSkillIds(String sessionId, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return;
        chatSessionStore.removeLoadedSkillIds(sessionId, cleanSkillIds);
    }

    public boolean softDelete(String userId, String sessionId) {
        var session = chatSessionStore.get(sessionId).orElse(null);
        if (session == null) return false;
        if (userId != null && !userId.equals(session.userId)) return false;
        return chatSessionStore.softDelete(sessionId, ZonedDateTime.now());
    }

    public boolean updateTitle(String userId, String sessionId, String title) {
        var session = chatSessionStore.get(sessionId).orElse(null);
        if (session == null || session.deletedAt != null) return false;
        if (userId != null && !userId.equals(session.userId)) return false;
        var cleaned = normalizeText(title);
        if (cleaned.isEmpty()) return false;
        if (cleaned.length() > MANUAL_TITLE_MAX_LENGTH) cleaned = cleaned.substring(0, MANUAL_TITLE_MAX_LENGTH);
        chatSessionStore.updateTitle(sessionId, cleaned);
        return true;
    }

    /**
     * Flips the per-chat completion switch. Deliberately void: a write that changes nothing (the switch
     * was already there) is indistinguishable from a missing row — the caller has already validated
     * existence and ownership.
     */
    public void updateNotifyOnComplete(String userId, String sessionId, boolean enabled) {
        var session = chatSessionStore.get(sessionId).orElse(null);
        if (session == null || session.deletedAt != null) return;
        if (userId != null && !userId.equals(session.userId)) return;
        chatSessionStore.updateNotifyOnComplete(sessionId, enabled);
    }

    public List<String> batchSoftDelete(String userId, List<String> sessionIds) {
        var deletedAt = ZonedDateTime.now();
        var deletedIds = new ArrayList<String>();
        for (var sessionId : sessionIds) {
            var session = chatSessionStore.get(sessionId).orElse(null);
            if (session == null || userId != null && !userId.equals(session.userId)) continue;
            if (chatSessionStore.softDelete(sessionId, deletedAt)) deletedIds.add(sessionId);
        }
        return deletedIds;
    }

    public long countSessions(String userId, List<String> sources, List<String> agentIds) {
        return chatSessionStore.count(query(userId, sources, agentIds));
    }

    public List<ChatSession> listSessions(String userId, List<String> sources, List<String> agentIds,
                                          int offset, int limit, String sortField) {
        var query = query(userId, sources, agentIds);
        query.offset = offset;
        query.limit = limit;
        query.sortField = sortField;
        return chatSessionStore.find(query);
    }

    private ChatSessionQuery query(String userId, List<String> sources, List<String> agentIds) {
        var query = new ChatSessionQuery();
        query.userId = userId;
        query.sources = sources;
        query.agentIds = agentIds;
        // a comparison session pair reads as one conversation: the secondary ("b") session stays hidden
        // from the list (residual on the user_id anchor) while remaining reachable through its pair
        query.excludeComparisonVariantB = true;
        return query;
    }

    private ChatSession requireActive(String sessionId) {
        var session = chatSessionStore.get(sessionId).orElse(null);
        if (session == null || session.deletedAt != null) {
            throw new NotFoundException("session not found, sessionId=" + sessionId);
        }
        return session;
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
