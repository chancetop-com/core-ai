package ai.core.server.memory.experiment;

import ai.core.prompt.PromptInject;
import ai.core.server.memory.AgentMemory;
import ai.core.server.memory.AgentMemoryService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core service for the Memory Experiment Framework.
 *
 * Implements the four-layer injection policy:
 *   1. Should Inject     — probability check (A/B test)
 *   2. Which Layers      — layer filtering
 *   3. Which Memories    — topK + sampling
 *   4. Ranking Strategy  — semantic, recency, random, etc.
 *
 * Also records experiment runs and patches outcomes from feedback.
 *
 * @author stephen
 */
@SuppressFBWarnings("MDM_RANDOM_SEED")
public class AgentMemoryExperimentService {
    private static int compareNewestFirst(AgentMemory a, AgentMemory b) {
        var ta = a.createdAt != null ? a.createdAt : a.updatedAt;
        var tb = b.createdAt != null ? b.createdAt : b.updatedAt;
        if (ta == null && tb == null) return 0;
        if (ta == null) return 1;
        if (tb == null) return -1;
        return tb.compareTo(ta);
    }

    @Inject
    MongoCollection<AgentMemoryExperimentConfig> configCollection;

    @Inject
    MongoCollection<AgentMemoryExperimentRun> runCollection;

    @Inject
    AgentMemoryService memoryService;

    private final Random random = new Random();

    // ── Config management ──

    public AgentMemoryExperimentConfig getConfig(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.limit = 1;
        var results = configCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    @SuppressFBWarnings("CFS_CONFUSING_FUNCTION_SEMANTICS")
    public AgentMemoryExperimentConfig saveConfig(AgentMemoryExperimentConfig config) {
        var existing = getConfig(config.agentId);
        var now = ZonedDateTime.now();
        if (existing != null) {
            config.id = existing.id;
            config.updatedAt = now;
            config.createdAt = existing.createdAt;
            configCollection.replace(config);
        } else {
            if (config.id == null) config.id = UUID.randomUUID().toString();
            config.createdAt = now;
            config.updatedAt = now;
            configCollection.insert(config);
        }
        return config;
    }

    public AgentMemoryExperimentConfig resolveConfig(String agentId) {
        var config = getConfig(agentId);
        if (config != null) return config;
        // default: enable all layers with semantic ranking
        var defaults = new AgentMemoryExperimentConfig();
        defaults.agentId = agentId;
        defaults.enabled = Boolean.TRUE;
        defaults.enabledLayers = List.copyOf(MemoryPolicy.DEFAULT_LAYERS);
        defaults.rankingStrategy = MemoryPolicy.DEFAULT_RANKING;
        defaults.topK = MemoryPolicy.DEFAULT_TOP_K;
        defaults.injectionProbability = MemoryPolicy.DEFAULT_INJECTION_PROBABILITY;
        defaults.injectionMode = MemoryPolicy.DEFAULT_INJECTION_MODE;
        return defaults;
    }

    // ── Injection pipeline ──

    /**
     * Prepares memory injection for a run.
     * Returns {@link MemoryInjectionResult#skipped()} if the probability check fails.
     */
    public MemoryInjectionResult prepareInjection(String agentId) {
        var config = resolveConfig(agentId);

        // Layer 1: Should Inject?
        if (!Boolean.TRUE.equals(config.enabled)) return MemoryInjectionResult.skipped();
        if (config.injectionProbability != null && config.injectionProbability < 1.0
                && random.nextDouble() > config.injectionProbability) return MemoryInjectionResult.skipped();

        // Layer 2: Which layers?
        var activeLayers = config.enabledLayers;
        if (activeLayers == null || activeLayers.isEmpty()) return MemoryInjectionResult.skipped();

        // Layer 5: How the selected memories are rendered
        var mode = config.injectionMode != null ? config.injectionMode : MemoryPolicy.DEFAULT_INJECTION_MODE;
        if (mode == InjectionMode.LAYERED) return layeredInjection(agentId, config, activeLayers);

        var allMemories = fetchMemoryPool(agentId, activeLayers);
        if (allMemories.isEmpty()) return MemoryInjectionResult.skipped();

        // Layer 3: Which memories? (topK)
        int topK = config.topK != null ? config.topK : MemoryPolicy.DEFAULT_TOP_K;

        // Layer 4: Ranking
        var ranked = rankMemories(allMemories, config.rankingStrategy, topK);
        return buildResult(formatAsMarkdown(ranked), ranked);
    }

    /**
     * Prepares injection for a run and records the experiment run when the agent is under experiment.
     * Returns the prompt section to inject, or null when nothing is injected.
     */
    public PromptInject prepareAndRecord(String agentId, String sessionId, String runId) {
        var result = prepareInjection(agentId);
        var config = getConfig(agentId);
        if (config != null) startRun(agentId, sessionId, runId, config, result);
        return result.injected ? result.promptInject : null;
    }

    /**
     * Knowledge is written out in full, the other layers only as an id index: a memory the user asked to keep
     * must survive the newest distilled sessions, while methods and trajectories are cheap to list and read on
     * demand through read_memory.
     */
    private MemoryInjectionResult layeredInjection(String agentId, AgentMemoryExperimentConfig config, List<MemoryLayer> activeLayers) {
        var knowledge = activeLayers.contains(MemoryLayer.KNOWLEDGE)
                ? newestFirst(memoryService.findByAgentIdAndLayer(agentId, MemoryLayer.KNOWLEDGE.mongoValue()), MemoryPolicy.KNOWLEDGE_FULL_MAX)
                : List.<AgentMemory>of();
        var indexed = selectIndexEntries(agentId, config, activeLayers);
        if (knowledge.isEmpty() && indexed.isEmpty()) return MemoryInjectionResult.skipped();

        var injected = new ArrayList<>(knowledge);
        injected.addAll(indexed);
        return buildResult(formatLayered(knowledge, indexed), injected);
    }

    private List<AgentMemory> selectIndexEntries(String agentId, AgentMemoryExperimentConfig config, List<MemoryLayer> activeLayers) {
        var indexable = new ArrayList<MemoryLayer>();
        for (var layer : activeLayers) {
            if (layer != MemoryLayer.KNOWLEDGE) indexable.add(layer);
        }
        if (indexable.isEmpty()) return List.of();

        var pool = fetchMemoryPool(agentId, indexable);
        pool.sort(AgentMemoryExperimentService::compareNewestFirst);
        return rankMemories(pool, config.rankingStrategy, MemoryPolicy.INDEX_MAX);
    }

    private MemoryInjectionResult buildResult(String formatted, List<AgentMemory> memories) {
        var memoryIds = memories.stream().map(memory -> memory.id).collect(Collectors.toList());
        var layerBreakdown = new LinkedHashMap<String, Integer>();
        for (var memory : memories) {
            var key = memory.layer != null ? memory.layer.mongoValue() : MemoryLayer.KNOWLEDGE.mongoValue();
            layerBreakdown.merge(key, 1, Integer::sum);
        }
        return MemoryInjectionResult.injected(formatted, memoryIds, layerBreakdown, estimateTokens(formatted));
    }

    private List<AgentMemory> newestFirst(List<AgentMemory> memories, int max) {
        return memories.stream()
                .sorted(AgentMemoryExperimentService::compareNewestFirst)
                .limit(max)
                .collect(Collectors.toList());
    }

    private List<AgentMemory> fetchMemoryPool(String agentId, List<MemoryLayer> layers) {
        var all = new ArrayList<AgentMemory>();
        for (var layer : layers) {
            all.addAll(memoryService.findByAgentIdAndLayer(agentId, layer.mongoValue()));
        }
        return all;
    }

    private List<AgentMemory> rankMemories(List<AgentMemory> memories, RankingStrategy strategy, int topK) {
        if (memories.size() <= topK) return new ArrayList<>(memories);

        var effective = strategy != null ? strategy : MemoryPolicy.DEFAULT_RANKING;
        if (effective == RankingStrategy.RECENCY || effective == RankingStrategy.SEMANTIC
                || effective == RankingStrategy.HYBRID || effective == RankingStrategy.BM25
                || effective == RankingStrategy.IMPORTANCE) {
            // All non-random strategies fall back to recency until vector search is available
            return memories.stream()
                    .sorted(AgentMemoryExperimentService::compareNewestFirst)
                    .limit(topK)
                    .collect(Collectors.toList());
        }

        // RANDOM
        var shuffled = new ArrayList<>(memories);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(topK, shuffled.size()));
    }

    // ── Prompt formatting (always Markdown for best readability and token efficiency) ──

    private String formatLayered(List<AgentMemory> knowledge, List<AgentMemory> indexed) {
        var sb = new StringBuilder(1024);
        if (!knowledge.isEmpty()) {
            sb.append(memoryService.formatKnowledgePrompt(knowledge));
        }
        if (!indexed.isEmpty()) {
            sb.append("## Indexed Memory\n\n"
                    + "Distilled methods and past sessions, listed by id — the memory holds the conclusion, its\n"
                    + "trace id holds the session behind it. Call read_memory with an id before relying on one.\n\n");
            for (var memory : indexed) {
                sb.append("- ").append(memory.id)
                        .append(" [").append(layerKey(memory)).append('/').append(text(memory.type)).append("] ")
                        .append(recency(memory)).append(" — ").append(summary(memory.content))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private String layerKey(AgentMemory memory) {
        return memory.layer != null ? memory.layer.mongoValue() : MemoryLayer.KNOWLEDGE.mongoValue();
    }

    private String recency(AgentMemory memory) {
        var time = memory.createdAt != null ? memory.createdAt : memory.updatedAt;
        return time == null ? "-" : time.toLocalDate().toString();
    }

    private String summary(String content) {
        var collapsed = text(content).replaceAll("\\s+", " ").strip();
        return collapsed.length() <= MemoryPolicy.INDEX_SUMMARY_MAX_CHARS
                ? collapsed
                : collapsed.substring(0, MemoryPolicy.INDEX_SUMMARY_MAX_CHARS) + "...";
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String formatAsMarkdown(List<AgentMemory> memories) {
        var byLayer = groupByLayer(memories);
        var sb = new StringBuilder(256);
        sb.append("## Agent Memory\n\n");
        for (var entry : byLayer.entrySet()) {
            sb.append("### ").append(layerLabel(entry.getKey())).append('\n');
            for (var m : entry.getValue()) {
                sb.append("- ").append(m.content).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private Map<MemoryLayer, List<AgentMemory>> groupByLayer(List<AgentMemory> memories) {
        var map = new LinkedHashMap<MemoryLayer, List<AgentMemory>>();
        for (var layer : MemoryLayer.values()) {
            map.put(layer, new ArrayList<>());
        }
        for (var m : memories) {
            map.computeIfAbsent(m.layer != null ? m.layer : MemoryLayer.KNOWLEDGE, k -> new ArrayList<>()).add(m);
        }
        map.values().removeIf(List::isEmpty);
        return map;
    }

    private String layerLabel(MemoryLayer layer) {
        return switch (layer) {
            case KNOWLEDGE -> "Knowledge";
            case METHODS -> "Methods";
            case TRAJECTORIES -> "Trajectories";
        };
    }

    // ── Token estimation ──

    /**
     * Rough token count: ~4 characters per token for English text.
     */
    private int estimateTokens(String text) {
        return text.length() / 4;
    }

    // ── Experiment run recording ──

    public AgentMemoryExperimentRun startRun(String agentId, String sessionId, String runId,
                                              AgentMemoryExperimentConfig config, MemoryInjectionResult result) {
        var record = new AgentMemoryExperimentRun();
        record.id = UUID.randomUUID().toString();
        record.agentId = agentId;
        record.sessionId = sessionId;
        record.runId = runId;
        record.enabled = config.enabled;
        record.enabledLayers = config.enabledLayers;
        record.rankingStrategy = config.rankingStrategy;
        record.topK = config.topK;
        record.injectionProbability = config.injectionProbability;
        record.injectionMode = config.injectionMode != null ? config.injectionMode : MemoryPolicy.DEFAULT_INJECTION_MODE;
        record.injectionDecision = result.injected;
        record.injectedMemoryIds = result.injectedMemoryIds;
        record.injectedMemoryCount = result.injectedMemoryIds != null ? result.injectedMemoryIds.size() : 0;
        record.layerBreakdown = result.layerBreakdown;
        record.promptTokens = (long) result.estimatedTokens;
        record.createdAt = ZonedDateTime.now();
        record.updatedAt = record.createdAt;
        runCollection.insert(record);
        return record;
    }

    /**
     * Patches outcome into the experiment run record.
     * Called when session feedback is submitted.
     */
    public void recordOutcome(String sessionId, String outcome, Integer rating) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.descending("created_at");
        query.limit = 1;
        var runs = runCollection.find(query);
        if (runs.isEmpty()) return;

        var run = runs.getFirst();
        run.outcome = outcome;
        run.userRating = rating;
        run.updatedAt = ZonedDateTime.now();
        runCollection.replace(run);
    }

    /**
     * Records per-memory attribution (used/helpful) for a run.
     * Called later via LLM judge or heuristic analysis.
     */
    public void recordAttributions(String runId, List<MemoryAttribution> attributions) {
        var query = new Query();
        query.filter = Filters.eq("run_id", runId);
        query.limit = 1;
        var runs = runCollection.find(query);
        if (runs.isEmpty()) return;

        var run = runs.getFirst();
        run.attributions = attributions;
        run.updatedAt = ZonedDateTime.now();
        runCollection.replace(run);
    }

    /**
     * Finds all experiment runs for an agent, sorted by recency.
     */
    public List<AgentMemoryExperimentRun> findByAgentId(String agentId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("created_at");
        query.limit = limit;
        return runCollection.find(query);
    }

    /**
     * Paginated listing of experiment runs, optionally filtered by agent.
     */
    public List<AgentMemoryExperimentRun> findAllRuns(String agentId, int skip, int limit) {
        var query = new Query();
        if (agentId != null && !agentId.isBlank()) {
            query.filter = Filters.eq("agent_id", agentId);
        }
        query.sort = Sorts.descending("created_at");
        query.skip = skip;
        query.limit = limit;
        return runCollection.find(query);
    }

    public long countRuns(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return runCollection.count(Filters.eq("agent_id", agentId));
        }
        return runCollection.count(Filters.empty());
    }

    public AgentMemoryExperimentRun getRunById(String id) {
        var query = new Query();
        query.filter = Filters.eq("_id", id);
        query.limit = 1;
        var results = runCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * Paginated listing of experiment configs.
     */
    public List<AgentMemoryExperimentConfig> findAllConfigs(int skip, int limit) {
        var query = new Query();
        query.sort = Sorts.ascending("agent_id");
        query.skip = skip;
        query.limit = limit;
        return configCollection.find(query);
    }

    public long countConfigs() {
        return configCollection.count(Filters.empty());
    }

    public void deleteConfig(String agentId) {
        configCollection.delete(Filters.eq("agent_id", agentId));
    }
}
