package ai.core.server.skillhub;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillTier;
import ai.core.server.skill.SkillStore;
import ai.core.server.skill.SkillTiers;
import ai.core.skill.SkillSearchScorer;
import ai.core.tool.ToolSearchScorer;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * In-memory catalog of every registered skill, refreshed by {@code skill-hub-catalog-sync}
 * (30s) and invalidated on every {@code SkillService} write path. Uses stale-while-error
 * snapshot retention when the backing store fails and shares {@link SkillSearchScorer}
 * with agent-side discovery.
 *
 * @author stephen
 */
public class SkillCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillCatalogService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_CONTROLLED_LIMIT = 8;
    private static final int MAX_CONTROLLED_LIMIT = 20;
    private static final int MAX_SKILLS_PER_NAMESPACE = 3;

    private static CatalogSkill toCatalogSkill(SkillDefinition def) {
        List<String> resourcePaths = List.of();
        if (def.resources != null) {
            resourcePaths = def.resources.stream().map(resource -> resource.path).filter(path -> path != null).sorted().toList();
        }
        return new CatalogSkill(def.id, def.namespace, def.name, def.qualifiedName, def.description,
                def.sourceType != null ? def.sourceType.name().toLowerCase(Locale.ROOT) : null,
                SkillTiers.resolve(def),
                def.allowedTools != null ? List.copyOf(def.allowedTools) : List.of(),
                def.metadata != null ? Map.copyOf(def.metadata) : Map.of(),
                def.digest, resourcePaths, def.updatedAt);
    }

    @Inject
    SkillStore skillStore;

    private volatile Snapshot snapshot = new Snapshot(List.of(), false, false, null);
    private volatile boolean dirty = true;

    public void refresh() {
        synchronized (this) {
            snapshot = loadSnapshot(snapshot);
            dirty = false;
        }
    }

    public void invalidate() {
        synchronized (this) {
            dirty = true;
        }
    }

    public boolean isDegraded() {
        return snapshot.degraded();
    }

    public ZonedDateTime lastRefreshFailedAt() {
        return snapshot.lastRefreshFailedAt();
    }

    public SearchOutcome search(String query, String namespaceFilter, String sourceTypeFilter, Integer limit) {
        return searchWithBonus(query, namespaceFilter, sourceTypeFilter, limit, SkillSearchScorer.CORE_TIER_BONUS);
    }

    public SearchOutcome searchWithBonus(String query, String namespaceFilter, String sourceTypeFilter,
                                         Integer limit, int coreTierBonus) {
        var skills = ensureLoaded().skills();
        int effectiveLimit = normalizeLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);
        var filtered = filter(skills, namespaceFilter, sourceTypeFilter);
        return executeSearch(filtered, query, namespaceFilter, effectiveLimit, coreTierBonus);
    }

    public ControlledSearchOutcome searchControlled(String query, String callerNamespace, String namespaceFilter,
                                                    Integer limit, Predicate<CatalogSkill> accessFilter) {
        var current = ensureLoaded();
        if (current.degraded() && current.skills().isEmpty()) {
            return new ControlledSearchOutcome(List.of(), List.of(), true);
        }
        int effectiveLimit = normalizeLimit(limit, DEFAULT_CONTROLLED_LIMIT, MAX_CONTROLLED_LIMIT);
        var visible = new ArrayList<CatalogSkill>();
        for (var skill : current.skills()) {
            if (!isVisibleInControlledPool(skill, callerNamespace)) continue;
            if (!isBlank(namespaceFilter) && !namespaceFilter.equals(skill.namespace())) continue;
            if (accessFilter != null && !accessFilter.test(skill)) continue;
            visible.add(skill);
        }
        var outcome = executeSearch(visible, query, namespaceFilter, effectiveLimit, SkillSearchScorer.CORE_TIER_BONUS);
        return new ControlledSearchOutcome(outcome.namespaces(), outcome.skills(), false);
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

    private boolean isVisibleInControlledPool(CatalogSkill skill, String callerNamespace) {
        var tier = skill.tier() == null ? SkillTier.DOMAIN : skill.tier();
        if (tier == SkillTier.IMPORTED) return false;
        if (tier == SkillTier.CORE || tier == SkillTier.ALWAYS_ON) return true;
        return callerNamespace != null && !callerNamespace.isBlank() && callerNamespace.equals(skill.namespace());
    }

    private SearchOutcome executeSearch(List<CatalogSkill> filtered, String query, String namespaceFilter,
                                        int effectiveLimit, int coreTierBonus) {
        var tokens = query == null ? List.<String>of() : ToolSearchScorer.tokenize(query);
        if (tokens.isEmpty()) return listAll(filtered, effectiveLimit);

        var matched = new ArrayList<NamespaceMatches>();
        for (var skill : filtered) {
            var scored = score(skill, tokens, query, coreTierBonus);
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

    private Snapshot ensureLoaded() {
        var current = snapshot;
        if (!dirty && current.loaded()) return current;
        synchronized (this) {
            current = snapshot;
            if (dirty || !current.loaded()) {
                snapshot = loadSnapshot(current);
                dirty = false;
                current = snapshot;
            }
            return current;
        }
    }

    private Snapshot loadSnapshot(Snapshot previous) {
        List<SkillDefinition> defs;
        try {
            defs = skillStore.listCatalog();
        } catch (RuntimeException e) {
            var failedAt = ZonedDateTime.now();
            if (previous != null && previous.loaded() && !previous.skills().isEmpty()) {
                LOGGER.warn("skill catalog refresh failed, keeping stale snapshot of {} skills: {}",
                        previous.skills().size(), e.getMessage());
                return new Snapshot(previous.skills(), true, false, failedAt);
            }
            LOGGER.warn("skill catalog refresh failed on cold start (degraded mode): {}", e.getMessage());
            return new Snapshot(List.of(), false, true, failedAt);
        }
        LOGGER.debug("skill catalog refreshed, skills={}", defs.size());
        return new Snapshot(defs.stream().map(SkillCatalogService::toCatalogSkill).toList(), true, false, null);
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

    private ScoredSkill score(CatalogSkill skill, List<String> tokens, String query, int coreTierBonus) {
        boolean coreEligible = skill.tier() == SkillTier.CORE || skill.tier() == SkillTier.ALWAYS_ON;
        var candidate = new SkillSearchScorer.Candidate(
                skill.namespace(),
                skill.name(),
                skill.description(),
                skill.allowedTools(),
                skill.metadata(),
                coreEligible);
        var score = SkillSearchScorer.score(candidate, tokens, query, coreTierBonus);
        return score == null ? null : new ScoredSkill(skill, score);
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

    private int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) return defaultLimit;
        return Math.min(limit, maxLimit);
    }

    public record CatalogSkill(String id, String namespace, String name, String qualifiedName, String description,
                               String sourceType, SkillTier tier, List<String> allowedTools,
                               Map<String, String> metadata, String digest, List<String> resourcePaths,
                               ZonedDateTime updatedAt) {
    }

    public record ScoredSkill(CatalogSkill skill, int score) {
    }

    public record NamespaceSearchHit(String namespace, int score, int matchedCount) {
    }

    public record SearchOutcome(List<NamespaceSearchHit> namespaces, List<ScoredSkill> skills) {
    }

    public record ControlledSearchOutcome(List<NamespaceSearchHit> namespaces, List<ScoredSkill> skills, boolean degraded) {
    }

    private record NamespaceMatches(String namespace, CatalogSkill skill, int skillScore) {
    }

    private record NamespaceGroup(String namespace, int namespaceScore, List<ScoredSkill> skills) {
    }

    private record Snapshot(List<CatalogSkill> skills, boolean loaded, boolean degraded, ZonedDateTime lastRefreshFailedAt) {
    }
}
