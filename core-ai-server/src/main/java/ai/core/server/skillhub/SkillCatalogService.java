package ai.core.server.skillhub;

import ai.core.server.domain.SkillDefinition;
import ai.core.tool.ToolSearchScorer;
import com.mongodb.client.model.Projections;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory catalog of every registered skill, refreshed by {@code skill-hub-catalog-sync}
 * (30s) and invalidated on every {@code SkillService} write path. The snapshot carries
 * metadata only — content and resource bodies stay in Mongo (single skill bodies go up to
 * 10MB, they must not travel through every search).
 * <p>
 * Scoring reuses {@link ToolSearchScorer} with {@code namespace} as the brand layer and
 * adds skill-specific hits on {@code metadata} values and {@code allowed_tools}. A skill
 * is kept when every query token hits some field.
 *
 * @author stephen
 */
public class SkillCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillCatalogService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_SKILLS_PER_NAMESPACE = 3;
    private static final int METADATA_HIT_SCORE = 5;
    private static final int ALLOWED_TOOL_HIT_SCORE = 5;

    private static CatalogSkill toCatalogSkill(SkillDefinition def) {
        List<String> resourcePaths = List.of();
        if (def.resources != null) {
            resourcePaths = def.resources.stream().map(resource -> resource.path).filter(path -> path != null).sorted().toList();
        }
        return new CatalogSkill(def.id, def.namespace, def.name, def.qualifiedName, def.description,
                def.sourceType != null ? def.sourceType.name().toLowerCase(Locale.ROOT) : null,
                def.allowedTools != null ? List.copyOf(def.allowedTools) : List.of(),
                def.metadata != null ? Map.copyOf(def.metadata) : Map.of(),
                def.digest, resourcePaths, def.updatedAt);
    }

    @Inject
    MongoCollection<SkillDefinition> skillCollection;

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

    /**
     * Two-level search: {@code namespaces} carries every namespace with matched skills
     * (brand layer first, matched counts attached) and {@code skills} carries the
     * diversified picks — at most 3 per namespace, filled round-robin — so one
     * namespace cannot flood the top-N. A query-less call lists all skills flat.
     */
    public SearchOutcome search(String query, String namespaceFilter, String sourceTypeFilter, Integer limit) {
        var skills = ensureLoaded().skills();
        int effectiveLimit = normalizeLimit(limit);
        var filtered = filter(skills, namespaceFilter, sourceTypeFilter);
        var tokens = query == null ? List.<String>of() : ToolSearchScorer.tokenize(query);
        if (tokens.isEmpty()) return listAll(filtered, effectiveLimit);

        var matched = new ArrayList<NamespaceMatches>();
        for (var skill : filtered) {
            var scored = score(skill, tokens, query);
            if (scored != null) {
                matched.add(new NamespaceMatches(skill.namespace(), scored.skill(), scored.score()));
            }
        }
        var byNamespace = groupByNamespace(matched);
        var ordered = orderedNamespaces(byNamespace, tokens);
        var namespaces = ordered.stream()
                .map(namespace -> new NamespaceSearchHit(namespace.namespace, namespace.namespaceScore, namespace.skills.size()))
                .toList();
        int cap = namespaceFilter != null && !namespaceFilter.isBlank() ? Integer.MAX_VALUE : MAX_SKILLS_PER_NAMESPACE;
        var picks = diversify(ordered, effectiveLimit, cap);
        return new SearchOutcome(namespaces, picks);
    }

    public List<CatalogSkill> lookupByName(String name) {
        if (name == null || name.isBlank()) return List.of();
        return ensureLoaded().skills().stream()
                .filter(skill -> name.equals(skill.name()))
                .sorted(Comparator.comparing(CatalogSkill::qualifiedName))
                .toList();
    }

    public CatalogSkill find(String namespace, String name) {
        String qualifiedName = namespace + "/" + name;
        return ensureLoaded().skills().stream()
                .filter(skill -> qualifiedName.equals(skill.qualifiedName()))
                .findFirst()
                .orElse(null);
    }

    public CatalogSkill findById(String id) {
        return ensureLoaded().skills().stream().filter(skill -> skill.id().equals(id)).findFirst().orElse(null);
    }

    private Snapshot ensureLoaded() {
        var current = snapshot;
        if (!current.skills().isEmpty()) return current;
        synchronized (this) {
            current = snapshot;
            if (current.skills().isEmpty()) {
                snapshot = loadSnapshot();
                current = snapshot;
            }
            return current;
        }
    }

    private Snapshot loadSnapshot() {
        var query = new Query();
        query.projection = Projections.exclude("content", "resources.content");
        List<SkillDefinition> defs;
        try {
            defs = skillCollection.find(query);
        } catch (RuntimeException e) {
            LOGGER.warn("skill catalog refresh failed: {}", e.getMessage());
            return new Snapshot(List.of());
        }
        LOGGER.debug("skill catalog refreshed, skills={}", defs.size());
        return new Snapshot(defs.stream().map(SkillCatalogService::toCatalogSkill).toList());
    }

    private List<CatalogSkill> filter(List<CatalogSkill> skills, String namespaceFilter, String sourceTypeFilter) {
        if (isBlank(namespaceFilter) && isBlank(sourceTypeFilter)) return skills;
        return skills.stream().filter(skill -> matches(skill, namespaceFilter, sourceTypeFilter)).toList();
    }

    private boolean matches(CatalogSkill skill, String namespaceFilter, String sourceTypeFilter) {
        boolean namespaceMatches = isBlank(namespaceFilter) || namespaceFilter.equals(skill.namespace());
        boolean sourceMatches = isBlank(sourceTypeFilter) || sourceTypeFilter.equals(skill.sourceType());
        return namespaceMatches && sourceMatches;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SearchOutcome listAll(List<CatalogSkill> skills, int limit) {
        var sorted = skills.stream()
                .sorted(Comparator.comparing(CatalogSkill::qualifiedName))
                .map(skill -> new ScoredSkill(skill, 0))
                .toList();
        return new SearchOutcome(List.of(), sorted.size() > limit ? List.copyOf(sorted.subList(0, limit)) : sorted);
    }

    /** Standard scorer on (name, description, namespace) plus metadata/allowed-tools bonuses; null when a token misses every field. */
    private ScoredSkill score(CatalogSkill skill, List<String> tokens, String query) {
        var match = ToolSearchScorer.match(skill.name(), skill.description(), skill.namespace(), query);
        int score = match.score();
        var metadataValues = skill.metadata() == null ? List.<String>of() : skill.metadata().values();
        for (var token : tokens) {
            boolean metadataHit = coversToken(metadataValues, token);
            boolean allowedToolHit = coversToken(skill.allowedTools(), token);
            boolean standardHit = coveredByStandardFields(skill, token);
            if (!standardHit && !metadataHit && !allowedToolHit) return null;
            if (metadataHit) score += METADATA_HIT_SCORE;
            if (allowedToolHit) score += ALLOWED_TOOL_HIT_SCORE;
        }
        return new ScoredSkill(skill, score);
    }

    private boolean coveredByStandardFields(CatalogSkill skill, String token) {
        return contains(skill.namespace(), token) || contains(skill.name(), token) || contains(skill.description(), token);
    }

    private boolean coversToken(Iterable<String> values, String token) {
        for (var value : values) {
            if (contains(value, token)) return true;
        }
        return false;
    }

    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private Map<String, List<ScoredSkill>> groupByNamespace(List<NamespaceMatches> matches) {
        var grouped = new HashMap<String, List<ScoredSkill>>();
        for (var hit : matches) {
            grouped.computeIfAbsent(hit.namespace, key -> new ArrayList<>()).add(new ScoredSkill(hit.skill(), hit.skillScore()));
        }
        for (var list : grouped.values()) {
            list.sort(Comparator.comparingInt(ScoredSkill::score).reversed()
                    .thenComparing(scored -> scored.skill().qualifiedName()));
        }
        return grouped;
    }

    /** Brand-matched namespaces (namespace score > 0) first, then others by their best skill score. */
    private List<NamespaceGroup> orderedNamespaces(Map<String, List<ScoredSkill>> grouped, List<String> tokens) {
        var groups = new ArrayList<NamespaceGroup>();
        for (var entry : grouped.entrySet()) {
            int namespaceScore = ToolSearchScorer.serverNameScore(entry.getKey(), tokens);
            groups.add(new NamespaceGroup(entry.getKey(), namespaceScore, entry.getValue()));
        }
        var brand = new ArrayList<NamespaceGroup>();
        var others = new ArrayList<NamespaceGroup>();
        for (var group : groups) {
            (group.namespaceScore > 0 ? brand : others).add(group);
        }
        Comparator<NamespaceGroup> byName = Comparator.comparing(group -> group.namespace);
        brand.sort(Comparator.comparingInt(NamespaceGroup::namespaceScore).reversed().thenComparing(byName));
        others.sort(Comparator.comparingInt((NamespaceGroup group) -> group.skills.getFirst().score()).reversed().thenComparing(byName));
        var ordered = new ArrayList<NamespaceGroup>(brand.size() + others.size());
        ordered.addAll(brand);
        ordered.addAll(others);
        return ordered;
    }

    /** Round-robin over the ordered namespaces; each contributes at most {@code cap} skills. */
    private List<ScoredSkill> diversify(List<NamespaceGroup> ordered, int limit, int cap) {
        var picks = new ArrayList<ScoredSkill>();
        for (int rank = 0; rank < cap && picks.size() < limit; rank++) {
            boolean added = false;
            for (var group : ordered) {
                var skills = group.skills;
                if (rank >= skills.size()) continue;
                picks.add(skills.get(rank));
                added = true;
                if (picks.size() >= limit) break;
            }
            if (!added || picks.size() >= limit) break;
        }
        return picks;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    public record CatalogSkill(String id, String namespace, String name, String qualifiedName, String description,
                               String sourceType, List<String> allowedTools, Map<String, String> metadata,
                               String digest, List<String> resourcePaths, ZonedDateTime updatedAt) {
    }

    public record ScoredSkill(CatalogSkill skill, int score) {
    }

    public record NamespaceSearchHit(String namespace, int score, int matchedCount) {
    }

    public record SearchOutcome(List<NamespaceSearchHit> namespaces, List<ScoredSkill> skills) {
    }

    private record NamespaceMatches(String namespace, CatalogSkill skill, int skillScore) {
    }

    private record NamespaceGroup(String namespace, int namespaceScore, List<ScoredSkill> skills) {
    }

    private record Snapshot(List<CatalogSkill> skills) {
    }
}
