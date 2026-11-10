package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Keyword search over the caller's own past conversations with one agent.
 *
 * <p>{@code chat_messages} carries no user or agent column, so the search runs in two hops: resolve
 * candidate conversations from {@code chat_sessions}, which is where ownership lives, then match
 * messages inside them. Only excerpts are returned — pulling whole conversations would be unbounded
 * and would bury the answer.
 *
 * @author stephen
 */
public class SessionSearchService {
    public static final int DEFAULT_LIMIT = 5;
    public static final int DEFAULT_WINDOW_DAYS = 90;
    static final int MAX_LIMIT = 20;
    static final int MAX_WINDOW_DAYS = 365;
    private static final int MAX_CANDIDATE_SESSIONS = 200;
    private static final int MAX_MATCHED_MESSAGES = 400;
    private static final int SNIPPET_RADIUS = 80;
    private static final int SNIPPETS_PER_SESSION = 3;
    private static final int SNIPPETS_PER_SESSION_DRILL_DOWN = 10;
    private static final Comparator<ScoredMessage> BY_SCORE_THEN_TIME = (left, right) -> {
        var byScore = Integer.compare(right.score(), left.score());
        return byScore != 0 ? byScore : right.time().compareTo(left.time());
    };
    private static final Comparator<SessionSearchHit> BY_MATCH_THEN_TIME = (left, right) -> {
        var byMatch = Integer.compare(right.matchCount, left.matchCount);
        return byMatch != 0 ? byMatch : right.lastMessageAt.compareTo(left.lastMessageAt);
    };

    @Inject
    ChatSessionStore chatSessionStore;
    @Inject
    ChatMessageStore chatMessageStore;

    public List<SessionSearchHit> search(SessionSearchQuery request) {
        if (request.userId == null || request.userId.isBlank()) return List.of();
        var terms = SessionSearchTerms.terms(request.query);
        if (terms.isEmpty()) return List.of();
        var windowDays = windowDays(request);
        var sessions = candidateSessions(request, windowDays);
        if (sessions.isEmpty()) return List.of();
        var messages = matchedMessages(sessions, terms, windowDays);
        if (messages.isEmpty()) return List.of();
        return toHits(sessions, messages, terms, limit(request), snippetsPerSession(request));
    }

    private List<ChatSession> candidateSessions(SessionSearchQuery request, int windowDays) {
        var drillDown = request.sessionId != null && !request.sessionId.isBlank();
        var query = new ChatSessionQuery();
        query.userId = request.userId;
        if (request.agentId != null && !request.agentId.isBlank()) query.agentIds = List.of(request.agentId);
        if (drillDown) {
            query.ids = List.of(request.sessionId);
            query.limit = 1;
        } else {
            query.from = cutoff(windowDays);
            query.limit = MAX_CANDIDATE_SESSIONS;
        }
        return chatSessionStore.find(query).stream().filter(session -> !session.id.equals(request.currentSessionId)).toList();
    }

    private List<ChatMessage> matchedMessages(List<ChatSession> sessions, List<String> terms, int windowDays) {
        var query = new ChatMessageQuery();
        query.sessionIds = sessions.stream().map(session -> session.id).toList();
        query.from = cutoff(windowDays);
        query.contentTerms = terms;
        query.sortField = "created_at";
        query.ascending = false;
        query.limit = MAX_MATCHED_MESSAGES;
        return chatMessageStore.find(query);
    }

    private List<SessionSearchHit> toHits(List<ChatSession> sessions, List<ChatMessage> messages, List<String> terms,
                                          int limit, int snippetsPerSession) {
        var sessionsById = sessions.stream().collect(Collectors.toMap(session -> session.id, session -> session, (left, right) -> left, LinkedHashMap::new));
        var grouped = new LinkedHashMap<String, List<ScoredMessage>>();
        for (var message : messages) {
            if (message.createdAt == null || !sessionsById.containsKey(message.sessionId)) continue;
            var score = SessionSearchTerms.matchCount(message.content, terms);
            if (score > 0) {
                grouped.computeIfAbsent(message.sessionId, key -> new ArrayList<>()).add(new ScoredMessage(message, score));
            }
        }
        var hits = new ArrayList<SessionSearchHit>();
        grouped.forEach((sessionId, matched) -> hits.add(toHit(sessionsById.get(sessionId), matched, terms, snippetsPerSession)));
        hits.sort(BY_MATCH_THEN_TIME);
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    private SessionSearchHit toHit(ChatSession session, List<ScoredMessage> matched, List<String> terms, int snippetsPerSession) {
        matched.sort(BY_SCORE_THEN_TIME);
        var hit = new SessionSearchHit();
        hit.sessionId = session.id;
        hit.title = session.title;
        hit.matchCount = matched.size();
        hit.lastMessageAt = session.lastMessageAt != null ? session.lastMessageAt : matched.get(0).time();
        for (var entry : matched.subList(0, Math.min(snippetsPerSession, matched.size()))) {
            var snippet = new SessionSearchHit.Snippet();
            snippet.role = entry.message().role;
            snippet.createdAt = entry.message().createdAt;
            snippet.text = SessionSearchTerms.snippet(entry.message().content, terms, SNIPPET_RADIUS);
            hit.snippets.add(snippet);
        }
        return hit;
    }

    private ZonedDateTime cutoff(int windowDays) {
        return ZonedDateTime.now().minusDays(windowDays);
    }

    private int limit(SessionSearchQuery request) {
        return request.limit <= 0 ? DEFAULT_LIMIT : Math.min(request.limit, MAX_LIMIT);
    }

    private int windowDays(SessionSearchQuery request) {
        return request.windowDays <= 0 ? DEFAULT_WINDOW_DAYS : Math.min(request.windowDays, MAX_WINDOW_DAYS);
    }

    private int snippetsPerSession(SessionSearchQuery request) {
        return request.sessionId != null && !request.sessionId.isBlank() ? SNIPPETS_PER_SESSION_DRILL_DOWN : SNIPPETS_PER_SESSION;
    }

    private record ScoredMessage(ChatMessage message, int score) {
        ZonedDateTime time() {
            return message.createdAt;
        }
    }
}
