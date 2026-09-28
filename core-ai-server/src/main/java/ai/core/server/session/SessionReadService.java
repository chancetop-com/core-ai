package ai.core.server.session;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the tail of one of the caller's own conversations, whoever the agent was.
 *
 * <p>{@link SessionSearchService} answers "where did we talk about X"; this answers "what happened in
 * that conversation", which is what a reply to a notification needs — the user's message carries no
 * keyword to search for, only a conversation they were told about.
 *
 * <p>Ownership is the whole permission model: the conversation must belong to the caller. The agent may
 * differ from the one asking, which is the point; another user's conversation is never readable.
 *
 * @author stephen
 */
public class SessionReadService {
    public static final int DEFAULT_TAIL = 6;
    public static final int MAX_TAIL = 20;
    private static final int MAX_MESSAGE_CHARS = 2000;
    private static final int MAX_TOTAL_CHARS = 8000;
    private static final int MAX_ARTIFACTS = 10;

    @Inject
    SessionRegistry sessionRegistry;
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

    /** Null when the conversation does not exist or is not the caller's — the two are not told apart. */
    public SessionRead read(String callerUserId, String sessionId, int tail) {
        if (isBlank(callerUserId) || isBlank(sessionId)) return null;
        ChatSession session;
        try {
            session = sessionRegistry.requireAccessible(sessionId, callerUserId);
        } catch (RuntimeException e) {
            return null;
        }
        var read = new SessionRead();
        read.title = session.title;
        read.agentId = session.agentId;
        read.lastMessageAt = session.lastMessageAt;
        read.artifacts = artifactLines(session.artifacts);
        read.messages = messages(sessionId, clampTail(tail), read);
        return read;
    }

    private List<SessionRead.Line> messages(String sessionId, int tail, SessionRead read) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.descending("seq");
        query.limit = tail;
        var newestFirst = chatMessageCollection.find(query);
        read.moreMessages = newestFirst.size() >= tail;
        var lines = new ArrayList<SessionRead.Line>(newestFirst.size());
        var budget = MAX_TOTAL_CHARS;
        for (var message : newestFirst) {
            var text = truncate(message.content, Math.min(MAX_MESSAGE_CHARS, Math.max(0, budget)));
            budget -= text.length();
            var line = new SessionRead.Line();
            line.role = message.role;
            line.text = text;
            line.tools = toolNames(message.tools);
            line.at = message.createdAt;
            lines.add(line);
        }
        Collections.reverse(lines);
        return lines;
    }

    private List<String> toolNames(List<ChatMessage.ToolCallRecord> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        var names = new ArrayList<String>(tools.size());
        for (var tool : tools) {
            if (tool.name != null && !names.contains(tool.name)) names.add(tool.name);
        }
        return names;
    }

    private List<String> artifactLines(List<AgentRunArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return List.of();
        var lines = new ArrayList<String>(Math.min(artifacts.size(), MAX_ARTIFACTS));
        for (var artifact : artifacts.subList(0, Math.min(artifacts.size(), MAX_ARTIFACTS))) {
            var name = artifact.fileName != null && !artifact.fileName.isBlank() ? artifact.fileName : artifact.fileId;
            if (name == null) continue;
            lines.add(artifact.fileId != null && !artifact.fileId.equals(name) ? name + " (" + artifact.fileId + ")" : name);
        }
        return lines;
    }

    private String truncate(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        return text.substring(0, Math.max(0, limit)) + " …[truncated]";
    }

    private int clampTail(int tail) {
        if (tail <= 0) return DEFAULT_TAIL;
        return Math.min(tail, MAX_TAIL);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** What the reader gets: enough of the conversation to know what was being worked on. */
    public static class SessionRead {
        public String title;
        public String agentId;
        public ZonedDateTime lastMessageAt;
        public List<String> artifacts = List.of();
        public List<Line> messages = List.of();
        public boolean moreMessages;

        public static class Line {
            public String role;
            public String text;
            public List<String> tools = List.of();
            public ZonedDateTime at;
        }
    }
}
