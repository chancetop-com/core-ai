package ai.core.server.memory;

import ai.core.server.memory.experiment.MemoryLayer;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoAgentMemoryStore implements AgentMemoryStore {
    public static MongoAgentMemoryStore of(MongoCollection<AgentMemory> memories, MongoCollection<AgentMemoryExtractionCursor> cursors) {
        var store = new MongoAgentMemoryStore();
        store.memoryCollection = memories;
        store.cursorCollection = cursors;
        return store;
    }

    @Inject
    MongoCollection<AgentMemory> memoryCollection;
    @Inject
    MongoCollection<AgentMemoryExtractionCursor> cursorCollection;

    @Override
    public List<AgentMemory> findByAgentId(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("created_at");
        return memoryCollection.find(query);
    }

    @Override
    public List<AgentMemory> findByAgentIdAndLayer(String agentId, String layer) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("agent_id", agentId), Filters.eq("layer", layer));
        query.sort = Sorts.ascending("created_at");
        return memoryCollection.find(query);
    }

    @Override
    public List<String> findAgentIdsWithMemories() {
        var ids = new LinkedHashSet<String>();
        int pageSize = 1000;
        int skip = 0;
        while (true) {
            var query = new Query();
            query.projection = Projections.include("agent_id");
            query.sort = Sorts.ascending("agent_id");
            query.skip = skip;
            query.limit = pageSize;
            var page = memoryCollection.find(query);
            for (var memory : page) {
                if (memory.agentId != null && !memory.agentId.isBlank()) ids.add(memory.agentId);
            }
            if (page.size() < pageSize) break;
            skip += pageSize;
        }
        return new ArrayList<>(ids);
    }

    @Override
    public Optional<AgentMemory> get(String id) {
        return memoryCollection.get(id);
    }

    @Override
    public void insert(AgentMemory memory) {
        memoryCollection.insert(memory);
    }

    @Override
    public boolean delete(String id) {
        return memoryCollection.delete(id);
    }

    @Override
    public long deleteByAgentId(String agentId) {
        return memoryCollection.delete(Filters.eq("agent_id", agentId));
    }

    @Override
    public long deleteLayerBefore(String agentId, String layer, ZonedDateTime cutoff) {
        return memoryCollection.delete(Filters.and(
            Filters.eq("agent_id", agentId), Filters.eq("layer", layer), Filters.lt("created_at", cutoff)));
    }

    @Override
    public List<AgentMemory> search(String agentId, String keyword, String layer, int limit) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("agent_id", agentId));
        if (layer != null) {
            filters.add(Filters.eq("layer", layer));
        } else {
            filters.add(Filters.or(
                Filters.eq("layer", MemoryLayer.METHODS.mongoValue()),
                Filters.eq("layer", MemoryLayer.TRAJECTORIES.mongoValue())));
        }
        filters.add(Filters.regex("content", ".*" + Pattern.quote(keyword.toLowerCase(java.util.Locale.ROOT)) + ".*", "i"));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("created_at");
        query.limit = limit;
        return memoryCollection.find(query);
    }

    @Override
    public AgentMemoryExtractionCursor getCursor(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.limit = 1;
        var results = cursorCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public void saveCursor(AgentMemoryExtractionCursor cursor) {
        if (cursor.id == null) cursorCollection.insert(cursor);
        else cursorCollection.replace(cursor);
    }
}
