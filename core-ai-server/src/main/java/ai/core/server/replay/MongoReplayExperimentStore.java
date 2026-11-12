package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoReplayExperimentStore implements ReplayExperimentStore {
    public static MongoReplayExperimentStore of(MongoCollection<ReplayExperiment> collection) {
        var store = new MongoReplayExperimentStore();
        store.experimentCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<ReplayExperiment> experimentCollection;

    @Override
    public Optional<ReplayExperiment> get(String id) {
        return experimentCollection.get(id);
    }

    @Override
    public void insert(ReplayExperiment experiment) {
        experimentCollection.insert(experiment);
    }

    @Override
    public void replace(ReplayExperiment experiment) {
        experimentCollection.replace(experiment);
    }

    @Override
    public void delete(String id) {
        experimentCollection.delete(id);
    }

    @Override
    public List<ReplayExperiment> find(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin,
                                       int offset, int limit) {
        var query = new Query();
        query.filter = filter(userId, admin, agentId, origin);
        query.sort = Sorts.descending("created_at");
        query.skip = Math.max(offset, 0);
        query.limit = Math.clamp(limit, 1, MAX_LIST_LIMIT);
        return experimentCollection.find(query);
    }

    @Override
    public long count(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin) {
        return experimentCollection.count(filter(userId, admin, agentId, origin));
    }

    @Override
    public void incrementRunCount(String id, ZonedDateTime updatedAt) {
        experimentCollection.update(Filters.eq("_id", id),
            Updates.combine(Updates.inc("run_count", 1), Updates.set("updated_at", updatedAt)));
    }

    @Override
    public int deleteUntouchedBlankBefore(ZonedDateTime cutoff, String blankDraft) {
        return (int) experimentCollection.delete(Filters.and(
            Filters.eq("origin", ReplayExperimentOrigin.BLANK),
            Filters.eq("run_count", 0),
            Filters.eq("note", null),
            Filters.or(Filters.eq("draft_request", null), Filters.eq("draft_request", blankDraft)),
            Filters.lt("created_at", cutoff)));
    }

    private Bson filter(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin) {
        var filters = new ArrayList<Bson>();
        if (!admin) filters.add(Filters.eq("user_id", userId));
        if (agentId != null && !agentId.isBlank()) filters.add(Filters.eq("agent_id", agentId));
        if (origin == ReplayExperimentOrigin.SPAN) filters.add(Filters.eq("origin", ReplayExperimentOrigin.SPAN));
        if (origin == ReplayExperimentOrigin.BLANK) filters.add(Filters.eq("origin", ReplayExperimentOrigin.BLANK));
        if (filters.isEmpty()) return Filters.empty();
        return filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
    }
}

