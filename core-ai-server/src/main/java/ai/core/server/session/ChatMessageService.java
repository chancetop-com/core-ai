package ai.core.server.session;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
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
    private static final int TITLE_MAX_LENGTH = 40;

    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    private final ConcurrentMap<String, AtomicLong> seqBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TurnBuffer> bufferBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionMeta> metaBySession = new ConcurrentHashMap<>();

    public void registerSession(String sessionId, String userId, String agentId) {
        registerSession(sessionId, SessionMeta.of(userId, agentId, "chat"));
    }

    public void registerSession(String sessionId, SessionMeta meta) {
        metaBySession.put(sessionId, meta);
    }

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
        chatMessageCollection.insert(msg);

        upsertSessionOnUserMessage(sessionId, content);
    }

    public List<ChatMessage> history(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.ascending("seq");
        return chatMessageCollection.find(query);
    }

    public ChatSession getSessionMeta(String sessionId) {
        return chatSessionCollection.get(sessionId).orElse(null);
    }

    public List<ChatSession> listSessions(String userId, List<String> sources, int offset, int limit) {
        var query = new Query();
        var filters = new java.util.ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("user_id", userId));
        filters.add(Filters.or(
            Filters.exists("deleted_at", false),
            Filters.eq("deleted_at", null)));
        if (sources != null && !sources.isEmpty()) {
            // include rows where source is missing (legacy data) when filtering by "chat" to avoid losing old sessions
            if (sources.contains("chat")) {
                filters.add(Filters.or(
                    Filters.in("source", sources),
                    Filters.exists("source", false),
                    Filters.eq("source", null)));
            } else {
                filters.add(Filters.in("source", sources));
            }
        }
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("last_message_at");
        query.skip = offset;
        query.limit = limit;
        return chatSessionCollection.find(query);
    }

    // soft-delete: mark session as deleted, but keep chat_messages rows for audit/trace.
    // returns true if hidden; false if not found or not owned by user.
    public boolean softDeleteSession(String userId, String sessionId) {
        var meta = chatSessionCollection.get(sessionId).orElse(null);
        if (meta == null) return false;
        if (userId != null && meta.userId != null && !userId.equals(meta.userId)) return false;
        chatSessionCollection.update(Filters.eq("_id", sessionId), Updates.set("deleted_at", ZonedDateTime.now()));
        onSessionClosed(sessionId);
        return true;
    }

    public AgentEventListener listener(String sessionId) {
        return new PersistenceListener(sessionId);
    }

    public void onSessionClosed(String sessionId) {
        seqBySession.remove(sessionId);
        bufferBySession.remove(sessionId);
        metaBySession.remove(sessionId);
    }

    private void upsertSessionOnUserMessage(String sessionId, String content) {
        var now = ZonedDateTime.now();
        var existing = chatSessionCollection.get(sessionId).orElse(null);
        if (existing == null) {
            var meta = metaBySession.get(sessionId);
            var session = new ChatSession();
            session.id = sessionId;
            session.userId = meta != null ? meta.userId : null;
            session.agentId = meta != null ? meta.agentId : null;
            session.source = meta != null && meta.source != null ? meta.source : "chat";
            session.scheduleId = meta != null ? meta.scheduleId : null;
            session.apiKeyId = meta != null ? meta.apiKeyId : null;
            session.title = truncateTitle(content);
            session.messageCount = 1L;
            session.createdAt = now;
            session.lastMessageAt = now;
            try {
                chatSessionCollection.insert(session);
            } catch (Exception e) {
                LOGGER.warn("failed to insert chat session, fallback to update, sessionId={}", sessionId, e);
                bumpSession(sessionId, now);
            }
        } else {
            bumpSession(sessionId, now);
        }
    }

    private void bumpSessionOnAgentTurn(String sessionId) {
        bumpSession(sessionId, ZonedDateTime.now());
    }

    private void bumpSession(String sessionId, ZonedDateTime now) {
        try {
            chatSessionCollection.update(Filters.eq("_id", sessionId),
                Updates.combine(
                    Updates.set("last_message_at", now),
                    Updates.inc("message_count", 1L)));
        } catch (Exception e) {
            LOGGER.warn("failed to bump chat session, sessionId={}", sessionId, e);
        }
    }

    private String truncateTitle(String content) {
        if (content == null) return "";
        var cleaned = content.replaceAll("\\s+", " ").trim();
        return cleaned.length() > TITLE_MAX_LENGTH ? cleaned.substring(0, TITLE_MAX_LENGTH) : cleaned;
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
                chatMessageCollection.insert(msg);
                bumpSessionOnAgentTurn(sessionId);
            } catch (Exception e) {
                LOGGER.warn("failed to persist agent message, sessionId={}", sessionId, e);
            }
        }
    }

    private static final class TurnBuffer {
        String thinking;
        final Map<String, ChatMessage.ToolCallRecord> tools = new LinkedHashMap<>();
    }

    public record SessionMeta(String userId, String agentId, String source, String scheduleId, String apiKeyId) {
        public static SessionMeta of(String userId, String agentId, String source) {
            return new SessionMeta(userId, agentId, source, null, null);
        }
    }
}
