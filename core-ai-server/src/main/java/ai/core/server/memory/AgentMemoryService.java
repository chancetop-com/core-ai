package ai.core.server.memory;

import ai.core.prompt.PromptInject;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentMemoryService {
    private static final String MEMORY_SECTION_HEADER = """
            ## Agent Memory

            The following patterns were learned from your previous successful runs.
            Use them to work more efficiently, but override when a situation clearly
            calls for a different approach.

            """;

    @Inject
    MongoCollection<AgentMemory> memoryCollection;

    @Inject
    MongoCollection<AgentMemoryExtractionCursor> cursorCollection;

    public List<AgentMemory> findByAgentId(String agentId) {
        var query = new core.framework.mongo.Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = com.mongodb.client.model.Sorts.ascending("created_at");
        return memoryCollection.find(query);
    }

    public PromptInject buildMemoryPromptInject(String agentId) {
        var memories = findByAgentId(agentId);
        if (memories.isEmpty()) return null;
        return new AgentMemoryPromptInject(formatAsPrompt(memories));
    }

    public String formatAsPrompt(List<AgentMemory> memories) {
        var sb = new StringBuilder(MEMORY_SECTION_HEADER);
        for (var memory : memories) {
            sb.append("- ").append(memory.content).append('\n');
        }
        return sb.toString();
    }

    public void replaceAll(String agentId, List<AgentMemory> memories) {
        memoryCollection.delete(Filters.eq("agent_id", agentId));
        for (var memory : memories) {
            if (memory.id == null) {
                memory.id = UUID.randomUUID().toString();
            }
            memoryCollection.insert(memory);
        }
    }

    public AgentMemoryExtractionCursor getCursor(String agentId) {
        var query = new core.framework.mongo.Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.limit = 1;
        var results = cursorCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    public void upsertCursor(AgentMemoryExtractionCursor cursor) {
        cursor.updatedAt = ZonedDateTime.now();
        var existing = getCursor(cursor.agentId);
        if (existing != null) {
            cursor.id = existing.id;
            cursorCollection.replace(cursor);
        } else {
            cursor.id = UUID.randomUUID().toString();
            cursorCollection.insert(cursor);
        }
    }
}
