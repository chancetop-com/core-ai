package ai.core.server.session;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonArray;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoChatSessionStore implements ChatSessionStore {
    public static MongoChatSessionStore of(MongoCollection<ChatSession> collection) {
        var store = new MongoChatSessionStore();
        store.chatSessionCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    @Override
    public Optional<ChatSession> get(String id) {
        return chatSessionCollection.get(id);
    }

    @Override
    public boolean insertIfAbsent(ChatSession session) {
        try {
            chatSessionCollection.insert(session);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void upsert(ChatSession session) {
        if (chatSessionCollection.get(session.id).isPresent()) {
            chatSessionCollection.replace(session);
        } else {
            chatSessionCollection.insert(session);
        }
    }

    @Override
    public List<ChatSession> find(ChatSessionQuery request) {
        var query = new Query();
        query.filter = filter(request);
        query.sort = request.ascending ? Sorts.ascending(request.sortField) : Sorts.descending(request.sortField);
        query.skip = request.offset;
        query.limit = request.limit == null || request.limit <= 0 ? null : request.limit;
        return chatSessionCollection.find(query);
    }

    @Override
    public long count(ChatSessionQuery request) {
        return chatSessionCollection.count(filter(request));
    }

    private Bson filter(ChatSessionQuery request) {
        var filters = new ArrayList<Bson>();
        if (request.userId != null) filters.add(Filters.eq("user_id", request.userId));
        if (request.agentIds != null) {
            if (request.agentIds.isEmpty()) {
                filters.add(Filters.eq("_id", "__none__"));
            } else if (request.agentIds.size() == 1) {
                filters.add(Filters.eq("agent_id", request.agentIds.getFirst()));
            } else {
                filters.add(Filters.in("agent_id", request.agentIds));
            }
        }
        if (request.ids != null) {
            if (request.ids.isEmpty()) {
                filters.add(Filters.eq("_id", "__none__"));
            } else if (request.ids.size() == 1) {
                filters.add(Filters.eq("_id", request.ids.getFirst()));
            } else {
                filters.add(Filters.in("_id", request.ids));
            }
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) filters.add(Filters.nin("_id", request.excludeIds));
        if (request.comparisonId != null) filters.add(Filters.eq("comparison_id", request.comparisonId));
        if (request.comparisonPairId != null) filters.add(Filters.eq("comparison_pair_id", request.comparisonPairId));
        if (request.sources != null && !request.sources.isEmpty()) {
            if (request.sources.contains("chat")) {
                filters.add(Filters.or(Filters.in("source", request.sources), Filters.exists("source", false), Filters.eq("source", null)));
            } else {
                filters.add(Filters.in("source", request.sources));
            }
        }
        if (request.from != null) filters.add(Filters.gte(request.timeField, request.from));
        if (request.to != null) filters.add(Filters.lt(request.timeField, request.to));
        if (request.hasArtifacts != null) {
            filters.add(request.hasArtifacts ? Filters.exists("artifacts.0", true) : Filters.exists("artifacts.0", false));
        }
        if (request.artifactFileIds != null && !request.artifactFileIds.isEmpty()) {
            filters.add(Filters.in("artifacts.file_id", request.artifactFileIds));
        }
        if (!request.includeDeleted) filters.add(Filters.eq("deleted_at", null));
        if (request.excludeComparisonVariantB) filters.add(Filters.ne("comparison_variant", "b"));
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }

    @Override
    public void recordComparison(String id, String comparisonId, String comparisonVariant, String comparisonPairId) {
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.combine(
                Updates.set("comparison_id", comparisonId),
                Updates.set("comparison_variant", comparisonVariant),
                Updates.set("comparison_pair_id", comparisonPairId))));
    }

    @Override
    public void recordUserMessage(String id, String titleIfAbsent, ZonedDateTime now) {
        var titled = chatSessionCollection.update(
                Filters.and(Filters.eq("_id", id), Filters.or(Filters.exists("title", false), Filters.eq("title", null))),
                Updates.combine(
                        Updates.set("title", titleIfAbsent),
                        Updates.set("last_message_at", now),
                        Updates.inc("message_count", 1L)));
        if (titled > 0) return;
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.combine(
                Updates.set("last_message_at", now),
                Updates.inc("message_count", 1L))));
    }

    @Override
    public void recordAgentMessage(String id, ZonedDateTime now) {
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.combine(
                Updates.set("last_message_at", now),
                Updates.inc("message_count", 1L))));
    }

    @Override
    public void addLoadedTools(String id, List<ToolRef> refs) {
        if (refs == null || refs.isEmpty()) return;
        initializeNullListField(id, "loaded_tools");
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.addEachToSet("loaded_tools", refs)));
    }

    @Override
    public void addLoadedSkillIds(String id, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return;
        initializeNullListField(id, "loaded_skill_ids");
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.addEachToSet("loaded_skill_ids", skillIds)));
    }

    @Override
    public void addLoadedSubAgentIds(String id, List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return;
        initializeNullListField(id, "loaded_sub_agent_ids");
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.addEachToSet("loaded_sub_agent_ids", agentIds)));
    }

    @Override
    public void removeLoadedSkillIds(String id, List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return;
        initializeNullListField(id, "loaded_skill_ids");
        requireUpdated(id, chatSessionCollection.update(Filters.eq("_id", id), Updates.pullAll("loaded_skill_ids", skillIds)));
    }

    private void requireUpdated(String id, long updated) {
        if (updated == 0) throw new IllegalStateException("session registry row missing, sessionId=" + id);
    }

    private void initializeNullListField(String id, String field) {
        chatSessionCollection.update(Filters.and(Filters.eq("_id", id), Filters.eq(field, null)),
                Updates.set(field, new BsonArray()));
    }

    @Override
    public void appendArtifact(String id, AgentRunArtifact artifact) {
        var session = chatSessionCollection.get(id)
                .orElseThrow(() -> new IllegalStateException("chat session not found, id=" + id));
        var artifacts = session.artifacts != null ? new ArrayList<>(session.artifacts) : new ArrayList<AgentRunArtifact>();
        artifacts.add(artifact);
        session.artifacts = artifacts;
        chatSessionCollection.replace(session);
    }

    @Override
    public void updateTitle(String id, String title) {
        chatSessionCollection.update(Filters.eq("_id", id), Updates.set("title", title));
    }

    @Override
    public void updateNotifyOnComplete(String id, boolean enabled) {
        chatSessionCollection.update(Filters.eq("_id", id), Updates.set("notify_on_complete", enabled));
    }

    @Override
    public boolean softDelete(String id, ZonedDateTime at) {
        return chatSessionCollection.update(Filters.eq("_id", id), Updates.set("deleted_at", at)) > 0;
    }
}
