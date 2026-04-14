package ai.core.server.session;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.server.domain.ChatMessage;
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
 * Persist chat messages for replay when user reopens the session.
 * Does not affect LLM context — pure display-layer persistence.
 *
 * @author Xander
 */
public class ChatMessageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatMessageService.class);

    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

    // per-session seq generator, seeded lazily from DB
    private final ConcurrentMap<String, AtomicLong> seqBySession = new ConcurrentHashMap<>();
    // per-session buffer for in-flight agent turn (thinking + tool calls)
    private final ConcurrentMap<String, TurnBuffer> bufferBySession = new ConcurrentHashMap<>();

    public void writeUserMessage(String sessionId, String content) {
        var msg = new ChatMessage();
        msg.id = UUID.randomUUID().toString();
        msg.sessionId = sessionId;
        msg.seq = nextSeq(sessionId);
        msg.role = "user";
        msg.content = content;
        msg.traceId = ActionLogContext.id();
        msg.createdAt = ZonedDateTime.now();
        chatMessageCollection.insert(msg);
    }

    public List<ChatMessage> history(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.ascending("seq");
        return chatMessageCollection.find(query);
    }

    public AgentEventListener listener(String sessionId) {
        return new PersistenceListener(sessionId);
    }

    public void onSessionClosed(String sessionId) {
        seqBySession.remove(sessionId);
        bufferBySession.remove(sessionId);
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
            } catch (Exception e) {
                LOGGER.warn("failed to persist agent message, sessionId={}", sessionId, e);
            }
        }
    }

    private static class TurnBuffer {
        String thinking;
        Map<String, ChatMessage.ToolCallRecord> tools = new LinkedHashMap<>();
    }
}
