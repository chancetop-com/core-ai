package ai.core.server.session;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persist chat messages and session metadata so the user can reopen past conversations.
 * Does not feed into LLM context — pure display-layer persistence.
 *
 * @author Xander
 */
public class ChatMessageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatMessageService.class);
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;
    @Inject
    SessionRegistry sessionRegistry;

    private final ConcurrentMap<String, AtomicLong> seqBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TurnBuffer> bufferBySession = new ConcurrentHashMap<>();
    public void writeUserMessage(String sessionId, String content) {
        var seq = nextSeq(sessionId);
        var msg = new ChatMessage();
        msg.id = UUID.randomUUID().toString();
        msg.sessionId = sessionId;
        msg.seq = seq;
        msg.role = "user";
        msg.content = content;
        msg.traceId = ActionLogContext.id();
        msg.createdAt = ZonedDateTime.now();
        insertWithRetry(msg, sessionId);

        sessionRegistry.recordUserMessage(sessionId, content);
    }

    public List<ChatMessage> history(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.ascending("seq");
        return chatMessageCollection.find(query);
    }

    public ChatSession getSessionMeta(String sessionId) {
        return sessionRegistry.get(sessionId);
    }

    public String findSessionUserId(String sessionId) {
        ChatSession persisted = sessionRegistry.get(sessionId);
        return persisted != null ? persisted.userId : null;
    }

    public String findSessionAgentId(String sessionId) {
        ChatSession persisted = sessionRegistry.get(sessionId);
        return persisted != null ? persisted.agentId : null;
    }

    public List<ai.core.server.domain.AgentRunArtifact> artifacts(String sessionId) {
        var session = sessionRegistry.get(sessionId);
        return session != null ? session.artifacts : null;
    }

    public long countSessions(String userId, List<String> sources) {
        return countSessions(userId, sources, null);
    }

    public long countSessions(String userId, List<String> sources, List<String> agentIds) {
        return sessionRegistry.countSessions(userId, sources, agentIds);
    }

    public List<ChatSession> listSessions(String userId, List<String> sources, int offset, int limit) {
        return listSessions(userId, sources, null, offset, limit, "last_message_at");
    }

    // sortField controls the recency vs. stable ordering: the Chat sidebar passes "created_at" so active
    // sessions keep their position instead of jumping to the top, while the For You widget keeps "last_message_at".
    public List<ChatSession> listSessions(String userId, List<String> sources, int offset, int limit, String sortField) {
        return listSessions(userId, sources, null, offset, limit, sortField);
    }

    public List<ChatSession> listSessions(String userId, List<String> sources, List<String> agentIds, int offset, int limit, String sortField) {
        return sessionRegistry.listSessions(userId, sources, agentIds, offset, limit, sortField);
    }

    // soft-delete: mark session as deleted, but keep chat_messages rows for audit/trace.
    // returns true if hidden; false if not found or not owned by user.
    public boolean softDeleteSession(String userId, String sessionId) {
        var deleted = sessionRegistry.softDelete(userId, sessionId);
        if (deleted) onSessionClosed(sessionId);
        return deleted;
    }

    // batch soft-delete: returns the ids actually deleted (non-existent or non-owned ids are skipped),
    // so callers can safely run follow-up cleanup only on sessions the user truly owns.
    public List<String> batchSoftDelete(String userId, List<String> sessionIds) {
        var deletedIds = sessionRegistry.batchSoftDelete(userId, sessionIds);
        deletedIds.forEach(this::onSessionClosed);
        return deletedIds;
    }

    // user-initiated rename: trims/collapses whitespace, caps length, enforces ownership.
    // returns true if updated; false if not found, not owned, or blank after trimming.
    public boolean updateSessionTitle(String userId, String sessionId, String title) {
        return sessionRegistry.updateTitle(userId, sessionId, title);
    }

    public AgentEventListener listener(String sessionId) {
        return new PersistenceListener(sessionId);
    }

    public void onSessionClosed(String sessionId) {
        seqBySession.remove(sessionId);
        bufferBySession.remove(sessionId);
    }

    public void addLoadedTools(String sessionId, List<ToolRef> toolRefs) {
        sessionRegistry.addLoadedTools(sessionId, toolRefs);
    }

    public void addLoadedSkillIds(String sessionId, List<String> skillIds) {
        sessionRegistry.addLoadedSkillIds(sessionId, skillIds);
    }

    public void addLoadedSubAgentIds(String sessionId, List<String> agentIds) {
        sessionRegistry.addLoadedSubAgentIds(sessionId, agentIds);
    }

    public void removeLoadedSkillIds(String sessionId, List<String> skillIds) {
        sessionRegistry.removeLoadedSkillIds(sessionId, skillIds);
    }

    private long nextSeq(String sessionId) {
        var counter = seqBySession.computeIfAbsent(sessionId, this::seedSeq);
        return counter.incrementAndGet();
    }

    private AtomicLong seedSeq(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.descending("seq");
        query.limit = 1;
        var existing = chatMessageCollection.find(query);
        long seed = existing.isEmpty() || existing.getFirst().seq == null ? 0L : existing.getFirst().seq;
        return new AtomicLong(seed);
    }

    private void insertWithRetry(ChatMessage msg, String sessionId) {
        try {
            chatMessageCollection.insert(msg);
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) {
                LOGGER.warn("duplicate seq detected, re-seeding counter and retrying, sessionId={}, seq={}", sessionId, msg.seq);
                seqBySession.remove(sessionId);
                msg.seq = nextSeq(sessionId);
                chatMessageCollection.insert(msg);
            } else {
                throw e;
            }
        }
    }

    private TurnBuffer buffer(String sessionId) {
        return bufferBySession.computeIfAbsent(sessionId, k -> new TurnBuffer());
    }

    private class PersistenceListener implements AgentEventListener {
        private final String sessionId;

        PersistenceListener(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onReasoningComplete(ReasoningCompleteEvent event) {
            buffer(sessionId).thinking = event.reasoning;
        }

        @Override
        public void onToolStart(ToolStartEvent event) {
            var tc = buffer(sessionId).tools.computeIfAbsent(event.callId, k -> new ChatMessage.ToolCallRecord());
            tc.callId = event.callId;
            tc.name = event.toolName;
            tc.arguments = event.arguments;
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            var tc = buffer(sessionId).tools.computeIfAbsent(event.callId, k -> new ChatMessage.ToolCallRecord());
            tc.callId = event.callId;
            if (tc.name == null) tc.name = event.toolName;
            tc.result = event.result;
            tc.status = event.status;
        }

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            var buf = bufferBySession.remove(sessionId);
            try {
                var msg = new ChatMessage();
                msg.id = UUID.randomUUID().toString();
                msg.sessionId = sessionId;
                msg.seq = nextSeq(sessionId);
                msg.role = "agent";
                msg.content = event.output;
                msg.thinking = buf != null ? buf.thinking : null;
                msg.tools = buf != null && !buf.tools.isEmpty() ? List.copyOf(buf.tools.values()) : null;
                msg.traceId = ActionLogContext.id();
                msg.createdAt = ZonedDateTime.now();
                insertWithRetry(msg, sessionId);
                sessionRegistry.recordAgentMessage(sessionId);
            } catch (Exception e) {
                LOGGER.warn("failed to persist agent message, sessionId={}", sessionId, e);
            }
        }
    }

    private static final class TurnBuffer {
        String thinking;
        final Map<String, ChatMessage.ToolCallRecord> tools = new LinkedHashMap<>();
    }

}
