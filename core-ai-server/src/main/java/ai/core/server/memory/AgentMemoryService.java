package ai.core.server.memory;

import ai.core.prompt.PromptInject;
import ai.core.server.agent.PersonalAssistantService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ChatMessage;
import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentMemoryService {
    private static final int LAYER2_MAX_SIZE = 50;
    private static final int EVIDENCE_LOOKBACK_SECONDS = 5;
    private static final int EVIDENCE_LOOKAHEAD_SECONDS = 30;
    private static final int EVIDENCE_MESSAGE_LIMIT = 100;
    private static final int MAX_EVIDENCE_MESSAGES = 12;
    private static final int MAX_MESSAGE_CHARS = 1200;
    private static final int MAX_TOOL_CALLS_PER_MESSAGE = 5;
    private static final int MAX_TOOL_ARGUMENT_CHARS = 200;
    private static final int MAX_TOOL_RESULT_CHARS = 400;

    // Memory is opt-out: null (legacy agents) and true both enable it; only an explicit false disables it.
    public static boolean memoryEnabled(Boolean enableMemory) {
        return !Boolean.FALSE.equals(enableMemory);
    }

    // Explicit remembering writes knowledge under the agent id; only a personal assistant fork owns a
    // per-user id, so only it may write — a shared agent would push one user's facts into another's prompt.
    public static boolean rememberEnabled(AgentDefinition definition) {
        if (definition == null || !PersonalAssistantService.isPersonalAssistant(definition)) return false;
        var config = definition.publishedConfig;
        return memoryEnabled(config != null ? config.enableMemory : definition.enableMemory);
    }

    // knowledge rows are rendered into a prompt section, so a statement is kept on a single line
    private static String normalizeContent(String content) {
        if (content == null) return "";
        return content.trim().replaceAll("\\s+", " ");
    }

    @Inject
    MongoCollection<AgentMemory> memoryCollection;

    @Inject
    MongoCollection<AgentMemoryExtractionCursor> cursorCollection;

    @Inject
    MongoCollection<Trace> traceCollection;

    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

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
        var memories = findByAgentIdAndLayer(agentId, MemoryLayer.KNOWLEDGE.mongoValue());
        if (memories.isEmpty()) return null;
        return new AgentMemoryPromptInject(formatKnowledgePrompt(memories));
    }

    /**
     * Renders knowledge rows as a prompt block, grouped by type. Also used for the resident part of layered
     * memory injection, so both paths describe the same rows the same way.
     */
    public String formatKnowledgePrompt(List<AgentMemory> memories) {
        var preferences = new ArrayList<String>();
        var domainKnowledge = new ArrayList<String>();
        var gotchas = new ArrayList<String>();
        for (var m : memories) {
            if (KnowledgeType.USER_PREFERENCE.name().equals(m.type)) {
                preferences.add(m.content);
            } else if (KnowledgeType.GOTCHA.name().equals(m.type)) {
                gotchas.add(m.content);
            } else {
                domainKnowledge.add(m.content);
            }
        }

        var sb = new StringBuilder(256);
        sb.append("## Agent Knowledge\n\n");
        if (!preferences.isEmpty()) {
            sb.append("### User Preferences\n");
            for (var preference : preferences) {
                sb.append("- ").append(preference).append('\n');
            }
            sb.append('\n');
        }
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
        sb.append("These are verified knowledge and known pitfalls from past experience. They do NOT override the skill SOP.\n");
        return sb.toString();
    }

    /**
     * Stores one self-contained statement as Layer 1 (knowledge). Repeating a statement the agent already
     * holds is a no-op, so asking twice never duplicates knowledge.
     */
    public RememberResult rememberKnowledge(String agentId, String content, String type) {
        var normalized = normalizeContent(content);
        for (var memory : findByAgentIdAndLayer(agentId, MemoryLayer.KNOWLEDGE.mongoValue())) {
            if (normalized.equalsIgnoreCase(normalizeContent(memory.content))) {
                return RememberResult.alreadyExists(memory);
            }
        }
        var now = ZonedDateTime.now();
        var memory = new AgentMemory();
        memory.id = UUID.randomUUID().toString();
        memory.agentId = agentId;
        memory.type = KnowledgeType.resolve(type).name();
        memory.layer = MemoryLayer.KNOWLEDGE;
        memory.content = normalized;
        memory.createdAt = now;
        memory.updatedAt = now;
        memoryCollection.insert(memory);
        return RememberResult.created(memory);
    }

    /** Reads one memory, scoped to its owner: an id belonging to another agent resolves to nothing. */
    public AgentMemory find(String agentId, String memoryId) {
        if (agentId == null || memoryId == null || memoryId.isBlank()) return null;
        var memory = memoryCollection.get(memoryId).orElse(null);
        if (memory == null || !agentId.equals(memory.agentId)) return null;
        return memory;
    }

    /**
     * Resolves the traces behind a memory. Traces are archived and deleted after the retention window, so ids
     * that no longer resolve are skipped instead of reported as errors.
     */
    public List<Trace> sourceTraces(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.in("trace_id", traceIds);
        var traces = new ArrayList<>(traceCollection.find(query));
        traces.sort((a, b) -> {
            if (a.startedAt == null) return b.startedAt == null ? 0 : 1;
            if (b.startedAt == null) return -1;
            return a.startedAt.compareTo(b.startedAt);
        });
        return traces;
    }

    /**
     * Bounded evidence of one trace: the conversation around it plus the tool calls the agent made in it.
     * Deliberately not the full trace payload — span inputs and outputs are far too large for a prompt.
     */
    public String renderEvidence(Trace trace) {
        var messages = queryTraceMessages(trace);
        if (messages.isEmpty()) {
            return "no conversation retained for this trace\n";
        }
        var sb = new StringBuilder(256);
        var from = Math.max(0, messages.size() - MAX_EVIDENCE_MESSAGES);
        if (from > 0) {
            sb.append("... ").append(from).append(" earlier messages omitted\n\n");
        }
        for (int i = from; i < messages.size(); i++) {
            appendMessage(sb, messages.get(i));
        }
        return sb.toString();
    }

    private List<ChatMessage> queryTraceMessages(Trace trace) {
        if (trace.sessionId == null || trace.sessionId.isBlank() || trace.startedAt == null) return List.of();
        var end = trace.completedAt != null ? trace.completedAt : trace.startedAt;
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("session_id", trace.sessionId),
                Filters.gte("created_at", trace.startedAt.minusSeconds(EVIDENCE_LOOKBACK_SECONDS)),
                Filters.lte("created_at", end.plusSeconds(EVIDENCE_LOOKAHEAD_SECONDS))
        );
        query.sort = Sorts.ascending("created_at");
        query.limit = EVIDENCE_MESSAGE_LIMIT;
        return chatMessageCollection.find(query);
    }

    private void appendMessage(StringBuilder sb, ChatMessage message) {
        sb.append("user".equals(message.role) ? "User: " : "Assistant: ")
                .append(truncate(message.content, MAX_MESSAGE_CHARS))
                .append('\n');
        if (message.tools == null || message.tools.isEmpty()) {
            sb.append('\n');
            return;
        }
        var shown = 0;
        for (var tool : message.tools) {
            if (shown >= MAX_TOOL_CALLS_PER_MESSAGE) {
                sb.append("  ... ").append(message.tools.size() - shown).append(" more tool calls\n");
                break;
            }
            sb.append("  [tool] ").append(tool.name).append(" (").append(tool.status == null ? "-" : tool.status).append(')');
            if (tool.arguments != null && !tool.arguments.isBlank()) {
                sb.append(" args: ").append(truncate(tool.arguments, MAX_TOOL_ARGUMENT_CHARS));
            }
            if (tool.result != null && !tool.result.isBlank()) {
                sb.append(" -> ").append(truncate(tool.result, MAX_TOOL_RESULT_CHARS));
            }
            sb.append('\n');
            shown++;
        }
        sb.append('\n');
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        var collapsed = text.strip();
        return collapsed.length() <= maxLength ? collapsed : collapsed.substring(0, maxLength) + "...";
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
            if (memory.layer == MemoryLayer.METHODS) {
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
                Filters.eq("layer", MemoryLayer.METHODS.mongoValue())
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
            filters.add(Filters.eq("layer", MemoryLayer.METHODS.mongoValue()));
        } else if ("trajectories".equals(layer)) {
            filters.add(Filters.eq("layer", MemoryLayer.TRAJECTORIES.mongoValue()));
        } else {
            // "all": search both Layer 2 and Layer 3
            filters.add(Filters.or(
                    Filters.eq("layer", MemoryLayer.METHODS.mongoValue()),
                    Filters.eq("layer", MemoryLayer.TRAJECTORIES.mongoValue())
            ));
        }

        // keyword filter on content
        var lowerQuery = query.toLowerCase(Locale.ROOT);
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
                Filters.eq("layer", MemoryLayer.TRAJECTORIES.mongoValue()),
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

    public boolean deleteMemory(String id) {
        return memoryCollection.delete(id);
    }

    public long deleteAllByAgentId(String agentId) {
        return memoryCollection.delete(Filters.eq("agent_id", agentId));
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
