package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentVersion;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoAgentVersionStore implements AgentVersionStore {
    public static MongoAgentVersionStore of(MongoCollection<AgentVersion> collection) {
        var store = new MongoAgentVersionStore();
        store.versionCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<AgentVersion> versionCollection;

    @Override
    public Optional<AgentVersion> get(String id) {
        return versionCollection.get(id);
    }

    @Override
    public List<AgentVersion> listByAgentId(String agentId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("created_at");
        query.limit = limit;
        return versionCollection.find(query);
    }

    @Override
    public long countByAgentId(String agentId) {
        return versionCollection.count(Filters.eq("agent_id", agentId));
    }

    @Override
    public void insert(AgentVersion version) {
        versionCollection.insert(version);
    }

    @Override
    public boolean insertIfAbsent(AgentVersion version) {
        try {
            versionCollection.insert(version);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(AgentVersion version) {
        versionCollection.replace(version);
    }

    @Override
    public void delete(String id) {
        versionCollection.delete(id);
    }
}
