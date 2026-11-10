package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoChatMessageStore implements ChatMessageStore {
    public static MongoChatMessageStore of(MongoCollection<ChatMessage> collection) {
        var store = new MongoChatMessageStore();
        store.chatMessageCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

    @Override
    public Optional<ChatMessage> get(String id) {
        return chatMessageCollection.get(id);
    }

    @Override
    public boolean insertIfAbsent(ChatMessage message) {
        try {
            chatMessageCollection.insert(message);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public long latestSeq(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.descending("seq");
        query.limit = 1;
        var existing = chatMessageCollection.find(query);
        return existing.isEmpty() || existing.getFirst().seq == null ? 0L : existing.getFirst().seq;
    }

    @Override
    public List<ChatMessage> find(ChatMessageQuery request) {
        var query = new Query();
        query.filter = filter(request);
        query.sort = request.ascending
            ? Sorts.ascending(request.sortField)
            : Sorts.descending(request.sortField);
        query.skip = request.offset;
        query.limit = request.limit;
        return chatMessageCollection.find(query);
    }

    @Override
    public long count(ChatMessageQuery request) {
        return chatMessageCollection.count(filter(request));
    }

    private Bson filter(ChatMessageQuery request) {
        var filters = new ArrayList<Bson>();
        if (request.anyScope) {
            var scopes = new ArrayList<Bson>();
            if (request.traceIds != null) {
                scopes.add(request.traceIds.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("trace_id", request.traceIds));
            }
            if (request.sessionWindows != null) {
                for (var window : request.sessionWindows) {
                    scopes.add(Filters.and(
                        Filters.eq("session_id", window.sessionId),
                        Filters.gte("created_at", window.from),
                        Filters.lte("created_at", window.to)));
                }
            }
            filters.add(scopes.isEmpty() ? Filters.eq("_id", "__none__") : Filters.or(scopes));
        } else {
            if (request.sessionId != null) filters.add(Filters.eq("session_id", request.sessionId));
            if (request.sessionIds != null) {
                filters.add(request.sessionIds.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("session_id", request.sessionIds));
            }
            if (request.traceIds != null) {
                filters.add(request.traceIds.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("trace_id", request.traceIds));
            }
            if (request.from != null) filters.add(Filters.gte("created_at", request.from));
            if (request.to != null) filters.add(Filters.lte("created_at", request.to));
        }
        if (request.contentTerms != null && !request.contentTerms.isEmpty()) {
            var pattern = request.contentTerms.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|", ".*(", ").*"));
            filters.add(Filters.regex("content", pattern, "i"));
        }
        if (request.role != null) filters.add(Filters.eq("role", request.role));
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }
}
