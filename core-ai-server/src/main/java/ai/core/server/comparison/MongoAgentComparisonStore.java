package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentComparison;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoAgentComparisonStore implements AgentComparisonStore {
    public static MongoAgentComparisonStore of(MongoCollection<AgentComparison> collection) {
        var store = new MongoAgentComparisonStore();
        store.comparisonCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<AgentComparison> comparisonCollection;

    @Override
    public Optional<AgentComparison> get(String id) {
        return comparisonCollection.get(id);
    }

    @Override
    public void insert(AgentComparison comparison) {
        comparisonCollection.insert(comparison);
    }

    @Override
    public void replace(AgentComparison comparison) {
        comparisonCollection.replace(comparison);
    }

    @Override
    public void delete(String id) {
        comparisonCollection.delete(id);
    }

    @Override
    public List<AgentComparison> find(AgentComparisonQuery request) {
        var query = new Query();
        query.filter = filter(request);
        query.sort = Sorts.descending("created_at");
        query.limit = request.limit;
        return comparisonCollection.find(query);
    }

    @Override
    public Optional<AgentComparison> findOne(AgentComparisonQuery request) {
        return comparisonCollection.findOne(filter(request));
    }

    private Bson filter(AgentComparisonQuery request) {
        var filters = new ArrayList<Bson>();
        if (request.agentId != null) filters.add(Filters.eq("agent_id", request.agentId));
        if (request.createdBy != null) filters.add(Filters.eq("created_by", request.createdBy));
        if (request.participantUserId != null) filters.add(Filters.eq("participant_user_ids", request.participantUserId));
        if (request.status != null) filters.add(Filters.eq("status", request.status));
        return filters.isEmpty() ? Filters.empty() : Filters.and(filters);
    }
}

