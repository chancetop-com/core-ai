package ai.core.server.apitoolhub;

import ai.core.server.tool.InternalApiToolLoader;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolSearchScorer;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory catalog of every enabled Service API operation, built from the
 * {@link InternalApiToolLoader} definitions (which already apply the three-level
 * {@code enabled} overrides and the curated descriptions/examples). Refreshed lazily after
 * {@link ToolRegistryService#reloadApiTools()} invalidates the snapshot (ToolRegistrySyncJob
 * detects {@code service_api} changes within 30s) — no extra scheduled job.
 * <p>
 * Search scoring reuses {@link ToolSearchScorer} with the app name as the brand layer and
 * {@code service.operation} as the tool-name layer, plus method/path bonuses layered on top
 * (method/path hits also keep an operation whose tokens miss every other field).
 *
 * @author stephen
 */
public class ApiToolCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiToolCatalogService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_OPERATIONS_PER_APP = 3;
    private static final int METHOD_HIT_SCORE = 5;
    private static final int PATH_HIT_SCORE = 3;

    @Inject
    ToolRegistryService toolRegistryService;

    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of(), Map.of());

    public void refresh() {
        synchronized (this) {
            snapshot = loadSnapshot();
        }
    }

    public void invalidate() {
        synchronized (this) {
            snapshot = new Snapshot(List.of(), Map.of(), Map.of());
        }
    }

    /** Visible apps with service/operation counts, sorted by app name. */
    public List<AppSummary> apps() {
        var loaded = ensureLoaded();
        var apps = new ArrayList<>(loaded.apps().values());
        apps.sort(Comparator.comparing(AppSummary::app));
        return apps;
    }

    /**
     * Two-level search: {@code apps} carries every app with matched operations (brand-layer
     * matches first with matched counts) and {@code operations} the diversified picks — at
     * most 3 per app, round-robin — so one app cannot flood the top-N. {@code appFilter}
     * (drill-down) lifts the per-app cap and {@code serviceFilter} narrows within the app.
     * A query-less call lists all operations flat.
     */
    public SearchOutcome search(String query, String appFilter, String serviceFilter, Integer limit) {
        var operations = ensureLoaded().operations();
        int effectiveLimit = normalizeLimit(limit);
        var filtered = filter(operations, appFilter, serviceFilter);
        var tokens = query == null ? List.<String>of() : ToolSearchScorer.tokenize(query);
        if (tokens.isEmpty()) return listAll(filtered, effectiveLimit);

        var matched = new ArrayList<AppMatches>();
        for (var operation : filtered) {
            var scored = score(operation, tokens, query);
            if (scored != null) {
                matched.add(new AppMatches(operation.app(), scored));
            }
        }
        var byApp = groupByApp(matched);
        var ordered = orderedApps(byApp, tokens);
        var appHits = ordered.stream()
                .map(group -> new AppSearchHit(group.app(), group.appScore(), group.operations().size()))
                .toList();
        int cap = isBlank(appFilter) ? MAX_OPERATIONS_PER_APP : Integer.MAX_VALUE;
        var picks = diversify(ordered, effectiveLimit, cap);
        return new SearchOutcome(appHits, picks);
    }

    /** @return the operation or null when absent/disabled — callers turn null into 404 */
    public CatalogOperation find(String app, String service, String operation) {
        return ensureLoaded().operations().stream()
                .filter(op -> app.equals(op.app()) && service.equals(op.service()) && operation.equals(op.name()))
                .findFirst()
                .orElse(null);
    }

    /** Resolves an existing function name ({@code app_service_operation}) to its catalog entry, or null. */
    public CatalogOperation findByToolName(String toolName) {
        return ensureLoaded().byToolName().get(toolName);
    }

    /** Every catalog operation sorted by qualified name (tools/list surface). */
    public List<CatalogOperation> allOperations() {
        return ensureLoaded().operations().stream()
                .sorted(Comparator.comparing(CatalogOperation::qualifiedName))
                .toList();
    }

    private Snapshot ensureLoaded() {
        var current = snapshot;
        if (!current.operations().isEmpty() || !current.apps().isEmpty()) return current;
        synchronized (this) {
            current = snapshot;
            if (current.operations().isEmpty() && current.apps().isEmpty()) {
                snapshot = loadSnapshot();
                current = snapshot;
            }
            return current;
        }
    }

    private Snapshot loadSnapshot() {
        var operations = new ArrayList<CatalogOperation>();
        var apps = new HashMap<String, AppSummary>();
        try {
            var catalogs = toolRegistryService.loadApiCatalog();
            for (var api : catalogs) {
                int operationCount = 0;
                int serviceCount = api.services().size();
                for (var service : api.services()) {
                    operationCount += addServiceOperations(api, service, operations);
                }
                apps.put(api.app(), new AppSummary(api.app(), api.baseUrl(), api.version(), api.description(),
                        serviceCount, operationCount));
            }
            LOGGER.debug("api-tool catalog refreshed, apps={}, operations={}", apps.size(), operations.size());
        } catch (RuntimeException e) {
            LOGGER.warn("api-tool catalog refresh failed: {}", e.getMessage());
            return new Snapshot(List.of(), Map.of(), Map.of());
        }
        var byToolName = new HashMap<String, CatalogOperation>();
        for (var operation : operations) {
            byToolName.putIfAbsent(operation.toolName(), operation);
        }
        return new Snapshot(List.copyOf(operations), Map.copyOf(apps), Map.copyOf(byToolName));
    }

    private int addServiceOperations(InternalApiToolLoader.ApiAppCatalog api, InternalApiToolLoader.ApiServiceCatalog service,
                                     List<CatalogOperation> operations) {
        int count = 0;
        for (var info : service.operations()) {
            operations.add(new CatalogOperation(api.app(), service.name(), info.name(), info.toolName(),
                    "api-operation:" + api.app() + ":" + service.name() + ":" + info.name(),
                    info.description(), info.method(), info.path(), info.needAuth(), info.deprecated(),
                    info.example(), info.requestType(), info.responseType(),
                    info.inputSchema(), info.outputSchema()));
            count++;
        }
        return count;
    }

    private List<CatalogOperation> filter(List<CatalogOperation> operations, String appFilter, String serviceFilter) {
        if (isBlank(appFilter) && isBlank(serviceFilter)) return operations;
        return operations.stream()
                .filter(op -> (isBlank(appFilter) || appFilter.equals(op.app()))
                        && (isBlank(serviceFilter) || serviceFilter.equals(op.service())))
                .toList();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SearchOutcome listAll(List<CatalogOperation> operations, int limit) {
        var sorted = operations.stream()
                .sorted(Comparator.comparing(CatalogOperation::qualifiedName))
                .map(operation -> new ScoredOperation(operation, 0))
                .toList();
        return new SearchOutcome(List.of(), sorted.size() > limit ? List.copyOf(sorted.subList(0, limit)) : sorted);
    }

    /** Standard scorer on (service.operation, description, app) plus method/path bonuses; null when a token misses every field. */
    private ScoredOperation score(CatalogOperation operation, List<String> tokens, String query) {
        String name = operation.service() + "." + operation.name();
        var match = ToolSearchScorer.match(name, operation.description(), operation.app(), query);
        int score = match.score();
        for (var token : tokens) {
            boolean methodHit = operation.method() != null && operation.method().equalsIgnoreCase(token);
            boolean pathHit = operation.path() != null && operation.path().toLowerCase(Locale.ROOT).contains(token);
            boolean standardHit = contains(name, token) || contains(operation.description(), token) || contains(operation.app(), token);
            if (!standardHit && !methodHit && !pathHit) return null;
            if (methodHit) score += METHOD_HIT_SCORE;
            if (pathHit) score += PATH_HIT_SCORE;
        }
        return new ScoredOperation(operation, score);
    }

    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private Map<String, List<ScoredOperation>> groupByApp(List<AppMatches> matches) {
        var grouped = new HashMap<String, List<ScoredOperation>>();
        for (var hit : matches) {
            grouped.computeIfAbsent(hit.app, key -> new ArrayList<>()).add(hit.scored());
        }
        for (var list : grouped.values()) {
            list.sort(Comparator.comparingInt(ScoredOperation::score).reversed()
                    .thenComparing(scored -> scored.operation().qualifiedName()));
        }
        return grouped;
    }

    /** Brand-matched apps (app score > 0) first, then others by their best operation score. */
    private List<AppGroup> orderedApps(Map<String, List<ScoredOperation>> grouped, List<String> tokens) {
        var groups = new ArrayList<AppGroup>();
        for (var entry : grouped.entrySet()) {
            int appScore = ToolSearchScorer.serverNameScore(entry.getKey(), tokens);
            groups.add(new AppGroup(entry.getKey(), appScore, entry.getValue()));
        }
        var brand = new ArrayList<AppGroup>();
        var others = new ArrayList<AppGroup>();
        for (var group : groups) {
            (group.appScore() > 0 ? brand : others).add(group);
        }
        Comparator<AppGroup> byName = Comparator.comparing(AppGroup::app);
        brand.sort(Comparator.comparingInt(AppGroup::appScore).reversed().thenComparing(byName));
        others.sort(Comparator.comparingInt((AppGroup group) -> group.operations().getFirst().score()).reversed().thenComparing(byName));
        var ordered = new ArrayList<AppGroup>(brand.size() + others.size());
        ordered.addAll(brand);
        ordered.addAll(others);
        return ordered;
    }

    /** Round-robin over the ordered apps; each contributes at most {@code cap} operations. */
    private List<ScoredOperation> diversify(List<AppGroup> ordered, int limit, int cap) {
        var picks = new ArrayList<ScoredOperation>();
        for (int rank = 0; rank < cap && picks.size() < limit; rank++) {
            boolean added = false;
            for (var group : ordered) {
                var operations = group.operations();
                if (rank >= operations.size()) continue;
                picks.add(operations.get(rank));
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

    public record AppSummary(String app, String baseUrl, String version, String description,
                             int serviceCount, int operationCount) {
    }

    public record CatalogOperation(String app, String service, String name, String toolName, String refId,
                                   String description, String method, String path, Boolean needAuth, Boolean deprecated,
                                   String example, String requestType, String responseType,
                                   String inputSchemaJson, String outputSchemaJson) {
        public String qualifiedName() {
            return app + "/" + service + "/" + name;
        }
    }

    public record ScoredOperation(CatalogOperation operation, int score) {
    }

    public record AppSearchHit(String app, int score, int matchedCount) {
    }

    public record SearchOutcome(List<AppSearchHit> apps, List<ScoredOperation> operations) {
    }

    private record AppMatches(String app, ScoredOperation scored) {
    }

    private record AppGroup(String app, int appScore, List<ScoredOperation> operations) {
    }

    private record Snapshot(List<CatalogOperation> operations, Map<String, AppSummary> apps,
                            Map<String, CatalogOperation> byToolName) {
    }
}
