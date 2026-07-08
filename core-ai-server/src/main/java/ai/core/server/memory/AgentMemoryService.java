package ai.core.server.memory;

import ai.core.prompt.PromptInject;
import ai.core.server.memory.experiment.MemoryLayer;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentMemoryService {
    private static final int LAYER2_MAX_SIZE = 50;

    // Memory is opt-out: null (legacy agents) and true both enable it; only an explicit false disables it.
    public static boolean memoryEnabled(Boolean enableMemory) {
        return !Boolean.FALSE.equals(enableMemory);
    }

    @Inject
    MongoCollection<AgentMemory> memoryCollection;

    @Inject
    MongoCollection<AgentMemoryExtractionCursor> cursorCollection;

    public List<AgentMemory> findByAgentId(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.ascending("created_at");
        return memoryCollection.find(query);
    }

    public List<AgentMemory> findByAgentIdAndLayer(String agentId, String layer) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("layer", layer)
        );
        query.sort = Sorts.ascending("created_at");
        return memoryCollection.find(query);
    }

    /**
     * Builds a PromptInject containing only Layer 1 (knowledge) memories.
     * These are auto-injected into the system prompt and are SOP-safe
     * (DOMAIN_KNOWLEDGE, GOTCHA only — no workflow patterns).
     */
    public PromptInject buildMemoryPromptInject(String agentId) {
        var memories = findByAgentIdAndLayer(agentId, MemoryLayer.knowledge.name());
        if (memories.isEmpty()) return null;
        return new AgentMemoryPromptInject(formatKnowledgePrompt(memories));
    }

    private String formatKnowledgePrompt(List<AgentMemory> memories) {
        var domainKnowledge = new ArrayList<String>();
        var gotchas = new ArrayList<String>();
        for (var m : memories) {
            if ("DOMAIN_KNOWLEDGE".equals(m.type)) {
                domainKnowledge.add(m.content);
            } else if ("GOTCHA".equals(m.type)) {
                gotchas.add(m.content);
            }
        }

        var sb = new StringBuilder();
        sb.append("## Agent Knowledge\n\n");
        if (!domainKnowledge.isEmpty()) {
            sb.append("### Domain Knowledge\n");
            for (var dk : domainKnowledge) {
                sb.append("- ").append(dk).append('\n');
            }
            sb.append('\n');
        }
        if (!gotchas.isEmpty()) {
            sb.append("### Gotchas\n");
            for (var g : gotchas) {
                sb.append("- ").append(g).append('\n');
            }
            sb.append('\n');
        }
        sb.append("These are verified knowledge and known pitfalls from past experience. ");
        sb.append("They do NOT override the skill SOP.\n");
        return sb.toString();
    }

    /**
     * Appends memories (Layer 2 / Layer 3) without replacing existing ones.
     * For Layer 2 (methods), applies FIFO eviction when exceeding LAYER2_MAX_SIZE.
     * For Layer 3 (trajectories), no size limit (archived by time).
     */
    public void appendMemories(String agentId, List<AgentMemory> memories) {
        if (memories.isEmpty()) return;

        boolean hasLayer2 = false;
        for (var memory : memories) {
            if (memory.id == null) {
                memory.id = UUID.randomUUID().toString();
            }
            memory.agentId = agentId;
            memoryCollection.insert(memory);
            if (memory.layer == MemoryLayer.methods) {
                hasLayer2 = true;
            }
        }

        if (hasLayer2) {
            evictLayer2Excess(agentId);
        }
    }

    private void evictLayer2Excess(String agentId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("layer", MemoryLayer.methods.name())
        );
        query.sort = Sorts.ascending("created_at");
        var all = memoryCollection.find(query);
        if (all.size() > LAYER2_MAX_SIZE) {
            int toRemove = all.size() - LAYER2_MAX_SIZE;
            for (int i = 0; i < toRemove; i++) {
                memoryCollection.delete(all.get(i).id);
            }
        }
    }

    /**
     * Searches memories by keyword matching on content.
     * Only searches Layer 2 (methods) and/or Layer 3 (trajectories) — never Layer 1.
     * Returns up to {@code limit} results sorted by recency.
     */
    public List<AgentMemory> searchMemories(String agentId, String query, String layer, int limit) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("agent_id", agentId));

        if ("methods".equals(layer)) {
            filters.add(Filters.eq("layer", MemoryLayer.methods.name()));
        } else if ("trajectories".equals(layer)) {
            filters.add(Filters.eq("layer", MemoryLayer.trajectories.name()));
        } else {
            // "all": search both Layer 2 and Layer 3
            filters.add(Filters.or(
                    Filters.eq("layer", MemoryLayer.methods.name()),
                    Filters.eq("layer", MemoryLayer.trajectories.name())
            ));
        }

        // keyword filter on content
        var lowerQuery = query.toLowerCase();
        filters.add(Filters.regex("content", ".*" + java.util.regex.Pattern.quote(lowerQuery) + ".*", "i"));

        var mongoQuery = new Query();
        mongoQuery.filter = Filters.and(filters);
        mongoQuery.sort = Sorts.descending("created_at");
        mongoQuery.limit = limit;
        return memoryCollection.find(mongoQuery);
    }

    /**
     * Removes all Layer 3 trajectories older than retentionDays.
     */
    public int cleanupOldTrajectories(String agentId, int retentionDays) {
        var cutoff = ZonedDateTime.now().minusDays(retentionDays);
        var filter = Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("layer", MemoryLayer.trajectories.name()),
                Filters.lt("created_at", cutoff)
        );
        var count = memoryCollection.count(filter);
        if (count > 0) {
            memoryCollection.delete(filter);
        }
        return (int) count;
    }

    // Legacy support: replaceAll still works but V2 uses appendMemories instead.
    // Kept for migration compatibility.
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
        var query = new Query();
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
