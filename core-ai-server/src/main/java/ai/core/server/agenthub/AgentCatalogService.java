package ai.core.server.agenthub;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentVisibility;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.DefinitionType;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import ai.core.tool.ToolSearchScorer;
import com.mongodb.client.model.Projections;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory catalog behind the Agent Hub, refreshed by {@code agent-hub-catalog-sync} (30s) and
 * invalidated on every {@code AgentDefinitionService} write path.
 * <p>
 * The snapshot is user-independent: it carries the agent metadata plus two capability summaries
 * (live draft and published snapshot) so owner-only fields never need a second query. Visibility
 * ({@link AgentVisibility}) is applied per request, because it depends on the caller. System
 * prompts, models and tool details are never part of a summary.
 * <p>
 * Scoring reuses {@link ToolSearchScorer} on (name, description) and adds hits on the resolved
 * skill names; an agent is kept when every query token hits some field.
 *
 * @author stephen
 */
public class AgentCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentCatalogService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int SKILL_NAME_HIT_SCORE = 5;
    private static final String SOURCE_SERVER = "server";

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    SkillService skillService;

    private volatile Snapshot snapshot = new Snapshot(List.of());

    public void refresh() {
        synchronized (this) {
            snapshot = loadSnapshot();
        }
    }

    public void invalidate() {
        synchronized (this) {
            snapshot = new Snapshot(List.of());
        }
    }

    /** Visible and runnable agents, best match first; a blank query lists published first. */
    public List<CatalogAgent> search(String userId, String query, String type, String source, Integer limit) {
        var candidates = visibleRunnable(userId, type, source);
        int effectiveLimit = normalizeLimit(limit);
        var tokens = ToolSearchScorer.tokenize(query);
        if (tokens.isEmpty()) return listAll(candidates, effectiveLimit);

        var matched = new ArrayList<ScoredAgent>();
        for (var agent : candidates) {
            var scored = score(agent, capabilityFor(agent, userId), tokens, query);
            if (scored != null) matched.add(scored);
        }
        matched.sort(Comparator.comparingInt(ScoredAgent::score).reversed()
                .thenComparing(scored -> scored.agent().name(), String.CASE_INSENSITIVE_ORDER));
        return matched.stream().limit(effectiveLimit).map(ScoredAgent::agent).toList();
    }

    /** Name lookup is case/space insensitive ({@code name_key} semantics) and never ambiguous-free: names are unique per owner only. */
    public List<CatalogAgent> lookup(String userId, String name) {
        if (name == null || name.isBlank()) return List.of();
        var key = name.trim().toLowerCase(Locale.ROOT);
        return visibleRunnable(userId, null, null).stream()
                .filter(agent -> key.equals(agent.name().trim().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(CatalogAgent::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public CatalogAgent find(String userId, String id) {
        if (id == null || id.isBlank()) return null;
        for (var agent : visibleRunnable(userId, null, null)) {
            if (id.equals(agent.id())) return agent;
        }
        return null;
    }

    /**
     * Capability of the config the run will actually use: the live draft for the owner's own
     * AGENT, the published snapshot for everyone else and always for an LLM_CALL.
     */
    public Capability capabilityFor(CatalogAgent agent, String userId) {
        if (agent.definition().type == DefinitionType.LLM_CALL) return agent.published();
        return AgentVisibility.runsLiveDraft(agent.definition(), userId) ? agent.draft() : agent.published();
    }

    private List<CatalogAgent> visibleRunnable(String userId, String type, String source) {
        if (!matchesSource(source)) return List.of();
        var result = new ArrayList<CatalogAgent>();
        for (var agent : ensureLoaded().agents()) {
            var definition = agent.definition();
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(definition.type.name())) continue;
            if (!AgentVisibility.isVisible(definition, userId)) continue;
            if (!AgentVisibility.isRunnable(definition, userId)) continue;
            result.add(agent);
        }
        return result;
    }

    private boolean matchesSource(String source) {
        return source == null || source.isBlank() || SOURCE_SERVER.equalsIgnoreCase(source);
    }

    private List<CatalogAgent> listAll(List<CatalogAgent> candidates, int limit) {
        return candidates.stream().sorted(this::compareForListing).limit(limit).toList();
    }

    /** No query: the system default agent first, then most recently published, then by name. */
    private int compareForListing(CatalogAgent left, CatalogAgent right) {
        int byDefault = Boolean.compare(Boolean.TRUE.equals(right.definition().systemDefault),
                Boolean.TRUE.equals(left.definition().systemDefault));
        if (byDefault != 0) return byDefault;
        int byPublished = comparePublishedAt(right.definition(), left.definition());
        if (byPublished != 0) return byPublished;
        return String.CASE_INSENSITIVE_ORDER.compare(left.name(), right.name());
    }

    private int comparePublishedAt(AgentDefinition left, AgentDefinition right) {
        if (left.publishedAt == null && right.publishedAt == null) return 0;
        if (left.publishedAt == null) return -1;
        if (right.publishedAt == null) return 1;
        return left.publishedAt.compareTo(right.publishedAt);
    }

    /** Standard scorer on (name, description) plus skill-name bonuses; null when a token misses every field. */
    private ScoredAgent score(CatalogAgent agent, Capability capability, List<String> tokens, String query) {
        var match = ToolSearchScorer.match(agent.name(), agent.description(), null, query);
        int score = match.score();
        var skillNames = capability.skillNames();
        for (var token : tokens) {
            boolean standardHit = contains(agent.name(), token) || contains(agent.description(), token);
            boolean skillHit = coversToken(skillNames, token);
            if (!standardHit && !skillHit) return null;
            if (skillHit) score += SKILL_NAME_HIT_SCORE;
        }
        return new ScoredAgent(agent, score);
    }

    private boolean coversToken(List<String> values, String token) {
        for (var value : values) {
            if (contains(value, token)) return true;
        }
        return false;
    }

    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private Snapshot ensureLoaded() {
        var current = snapshot;
        if (!current.agents().isEmpty()) return current;
        synchronized (this) {
            current = snapshot;
            if (current.agents().isEmpty()) {
                snapshot = loadSnapshot();
                current = snapshot;
            }
            return current;
        }
    }

    private Snapshot loadSnapshot() {
        var query = new Query();
        query.projection = Projections.exclude("system_prompt", "published_config.system_prompt", "variables");
        List<AgentDefinition> definitions;
        try {
            definitions = agentDefinitionCollection.find(query);
        } catch (RuntimeException e) {
            LOGGER.warn("agent catalog refresh failed: {}", e.getMessage());
            return new Snapshot(List.of());
        }
        var skillNames = resolveSkillNames(definitions);
        var agentNames = resolveAgentNames(definitions);
        var agents = definitions.stream().map(definition -> toCatalogAgent(definition, skillNames, agentNames)).toList();
        LOGGER.debug("agent catalog refreshed, agents={}", agents.size());
        return new Snapshot(agents);
    }

    private Map<String, String> resolveAgentNames(List<AgentDefinition> definitions) {
        Map<String, String> names = new HashMap<>();
        for (var definition : definitions) {
            if (definition.id != null && definition.name != null) names.put(definition.id, definition.name);
        }
        return names;
    }

    private Map<String, String> resolveSkillNames(List<AgentDefinition> definitions) {
        Set<String> skillIds = new LinkedHashSet<>();
        for (var definition : definitions) {
            skillIds.addAll(IdLists.clean(definition.skillIds));
            if (definition.publishedConfig != null) {
                skillIds.addAll(IdLists.clean(definition.publishedConfig.skillIds));
            }
        }
        if (skillIds.isEmpty()) return Map.of();
        try {
            return skillService.batchResolve(skillIds);
        } catch (RuntimeException e) {
            // an unreadable skill must not take the whole catalog down: names degrade to ids
            LOGGER.warn("failed to resolve skill names for agent catalog: {}", e.getMessage());
            return Map.of();
        }
    }

    private CatalogAgent toCatalogAgent(AgentDefinition definition, Map<String, String> skillNames,
                                        Map<String, String> agentNames) {
        var config = definition.publishedConfig;
        var published = config != null && AgentDependencyAccessPolicy.hasValidatedPublishedSkills(config)
                ? capabilityOf(config, skillNames, agentNames)
                : Capability.empty();
        return new CatalogAgent(definition, capabilityOf(definition, skillNames, agentNames), published);
    }

    private Capability capabilityOf(AgentDefinition definition, Map<String, String> skillNames, Map<String, String> agentNames) {
        return new Capability(definition.tools == null ? 0 : definition.tools.size(),
                resolveNames(definition.skillIds, skillNames), resolveNames(definition.subAgentIds, agentNames),
                definition.sandboxConfig != null, definition.inputTemplate, definition.responseSchema);
    }

    private Capability capabilityOf(AgentPublishedConfig config, Map<String, String> skillNames, Map<String, String> agentNames) {
        return new Capability(config.tools == null ? 0 : config.tools.size(),
                resolveNames(config.skillIds, skillNames), resolveNames(config.subAgentIds, agentNames),
                config.sandboxConfig != null, config.inputTemplate, config.responseSchema);
    }

    private List<String> resolveNames(List<String> ids, Map<String, String> names) {
        var cleaned = IdLists.clean(ids);
        if (cleaned.isEmpty()) return List.of();
        if (names == null) return cleaned;
        return cleaned.stream().map(id -> names.getOrDefault(id, id)).toList();
    }

    /** One catalog entry: the (projected) definition plus the two capability summaries. */
    public record CatalogAgent(AgentDefinition definition, Capability draft, Capability published) {
        public String id() {
            return definition.id;
        }

        public String name() {
            return definition.name;
        }

        public String description() {
            return definition.description;
        }
    }

    /** Capability summary of one executable config — never the system prompt, model or tool details. */
    public record Capability(int toolCount, List<String> skillNames, List<String> subAgentNames, boolean hasSandbox,
                             String inputHint, String responseSchema) {
        static Capability empty() {
            return new Capability(0, List.of(), List.of(), false, null, null);
        }
    }

    private record ScoredAgent(CatalogAgent agent, int score) {
    }

    private record Snapshot(List<CatalogAgent> agents) {
    }
}
