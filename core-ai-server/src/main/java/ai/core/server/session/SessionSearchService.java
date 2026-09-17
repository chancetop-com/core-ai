package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
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
    private static final String SESSION_ID = "session_id";
    private static final String CONTENT = "content";
    private static final String CREATED_AT = "created_at";
    private static final String LAST_MESSAGE_AT = "last_message_at";
    private static final String TITLE = "title";
    private static final String ROLE = "role";
    private static final Bson MESSAGE_PROJECTION = Projections.include("_id", SESSION_ID, ROLE, CONTENT, CREATED_AT);
    private static final Comparator<ScoredMessage> BY_SCORE_THEN_TIME = (left, right) -> {
        var byScore = Integer.compare(right.score(), left.score());
        return byScore != 0 ? byScore : right.time().compareTo(left.time());
    };
    private static final Comparator<SessionSearchHit> BY_MATCH_THEN_TIME = (left, right) -> {
        var byMatch = Integer.compare(right.matchCount, left.matchCount);
        return byMatch != 0 ? byMatch : right.lastMessageAt.compareTo(left.lastMessageAt);
    };

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

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
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("user_id", request.userId));
        if (request.agentId != null && !request.agentId.isBlank()) {
            filters.add(Filters.eq("agent_id", request.agentId));
        }
        filters.add(Filters.eq("deleted_at", null));
        if (drillDown) {
            filters.add(Filters.eq("_id", request.sessionId));
        } else {
            filters.add(Filters.gte(LAST_MESSAGE_AT, cutoff(windowDays)));
        }
        var query = new Query();
        query.filter = Filters.and(filters);
        query.projection = Projections.include("_id", TITLE, LAST_MESSAGE_AT);
        query.sort = Sorts.descending(LAST_MESSAGE_AT);
        query.limit = drillDown ? 1 : MAX_CANDIDATE_SESSIONS;
        return chatSessionCollection.find(query).stream().filter(session -> !session.id.equals(request.currentSessionId)).toList();
    }

    private List<ChatMessage> matchedMessages(List<ChatSession> sessions, List<String> terms, int windowDays) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in(SESSION_ID, sessions.stream().map(session -> session.id).toList()));
        filters.add(Filters.gte(CREATED_AT, cutoff(windowDays)));
        filters.add(Filters.regex(CONTENT, anyTermPattern(terms), "i"));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.projection = MESSAGE_PROJECTION;
        query.sort = Sorts.descending(CREATED_AT);
        query.limit = MAX_MATCHED_MESSAGES;
        return chatMessageCollection.find(query);
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

    private String anyTermPattern(List<String> terms) {
        return terms.stream().map(Pattern::quote).collect(Collectors.joining("|", ".*(", ").*"));
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
