package ai.core.server.mcphub;

import ai.core.mcp.client.McpClientManager;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolSearchScorer;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory catalog of every enabled MCP server and its tools, refreshed by
 * {@code mcp-hub-catalog-sync} (30s) and invalidated on server enable/disable/config
 * changes. A server whose tools cannot be listed while it is connecting/failing keeps
 * its previous snapshot and is flagged {@code stale}.
 * <p>
 * The catalog carries no server credentials — it only mirrors registry metadata and
 * tool schemas fetched through {@link ToolRegistryService}.
 *
 * @author stephen
 */
public class McpToolCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolCatalogService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_TOOLS_PER_SERVER = 3;

    @Inject
    ToolRegistryService toolRegistryService;

    private final Map<String, ServerSnapshot> snapshots = new ConcurrentHashMap<>();

    public void refresh() {
        var entries = enabledMcpEntries();
        var seen = HashSet.<String>newHashSet(entries.size());
        for (var entry : entries) {
            seen.add(entry.id);
            var previous = snapshots.get(entry.id);
            var snapshot = loadSnapshot(entry, previous);
            snapshots.put(entry.id, snapshot);
            if (previous == null) {
                LOGGER.debug("catalog loaded server, name={}, tools={}, stale={}", entry.name, snapshot.tools().size(), snapshot.stale());
            }
        }
        snapshots.keySet().removeIf(id -> !seen.contains(id));
    }

    public void invalidate(String serverId) {
        snapshots.remove(serverId);
    }

    public List<ServerViewData> listServers() {
        var result = new ArrayList<ServerViewData>();
        for (var entry : enabledMcpEntries()) {
            var snapshot = ensureLoaded(entry);
            result.add(new ServerViewData(entry, snapshot.state().name(), snapshot.tools().size(), snapshot.stale()));
        }
        result.sort(Comparator.comparing(data -> data.entry().name));
        return result;
    }

    /**
     * Two-level search: {@code servers} carries every server with matched tools
     * (brand layer first, matched counts attached) and {@code tools} carries the
     * diversified picks — at most {@link #MAX_TOOLS_PER_SERVER} per server, filled
     * round-robin by server order — so one large server cannot flood the top-N.
     * A query-less call lists all tools flat, without the server level.
     */
    public SearchOutcome search(String query, String serverFilter, Integer limit) {
        int effectiveLimit = normalizeLimit(limit);
        var tokens = query == null ? List.<String>of() : ToolSearchScorer.tokenize(query);
        if (tokens.isEmpty()) return listAll(serverFilter, effectiveLimit);
        var matches = matchServers(serverFilter, tokens, query);
        var ordered = orderedServers(matches);
        var servers = ordered.stream()
                .map(server -> new ServerSearchHit(server.snapshot().entry().name, server.serverScore(), server.matched().size(),
                        server.snapshot().state().name(), server.snapshot().stale()))
                .toList();
        int cap = serverFilter != null && !serverFilter.isBlank() ? Integer.MAX_VALUE : MAX_TOOLS_PER_SERVER;
        return new SearchOutcome(servers, diversify(ordered, effectiveLimit, cap));
    }

    private SearchOutcome listAll(String serverFilter, int limit) {
        var tools = new ArrayList<ScoredTool>();
        for (var entry : enabledMcpEntries()) {
            if (serverFilter != null && !serverFilter.isBlank() && !serverFilter.equals(entry.name)) continue;
            var snapshot = ensureLoaded(entry);
            for (var tool : staleMarkedTools(snapshot)) tools.add(new ScoredTool(tool, 0));
        }
        tools.sort(Comparator.comparing(scored -> scored.tool().qualifiedName()));
        return new SearchOutcome(List.of(), tools.size() > limit ? List.copyOf(tools.subList(0, limit)) : tools);
    }

    private List<ServerMatches> matchServers(String serverFilter, List<String> tokens, String query) {
        var matches = new ArrayList<ServerMatches>();
        for (var entry : enabledMcpEntries()) {
            if (serverFilter != null && !serverFilter.isBlank() && !serverFilter.equals(entry.name)) continue;
            var snapshot = ensureLoaded(entry);
            var matched = new ArrayList<ScoredTool>();
            for (var tool : staleMarkedTools(snapshot)) {
                var match = ToolSearchScorer.match(tool.name(), tool.description(), tool.serverName(), query);
                if (match.allTokensHit()) matched.add(new ScoredTool(tool, match.score()));
            }
            if (!matched.isEmpty()) {
                int serverScore = ToolSearchScorer.serverNameScore(entry.name, tokens);
                matched.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                        .thenComparing(scored -> scored.tool().qualifiedName()));
                matches.add(new ServerMatches(snapshot, serverScore, matched));
            }
        }
        return matches;
    }

    /** Brand-matched servers (server score > 0) first, then others by their best tool score. */
    private List<ServerMatches> orderedServers(List<ServerMatches> matches) {
        var brand = new ArrayList<ServerMatches>();
        var others = new ArrayList<ServerMatches>();
        for (var server : matches) {
            (server.serverScore() > 0 ? brand : others).add(server);
        }
        Comparator<ServerMatches> byName = Comparator.comparing(server -> server.snapshot().entry().name);
        brand.sort(Comparator.comparingInt(ServerMatches::serverScore).reversed().thenComparing(byName));
        others.sort(Comparator.comparingInt((ServerMatches server) -> server.matched().getFirst().score()).reversed()
                .thenComparing(byName));
        var ordered = new ArrayList<ServerMatches>(brand.size() + others.size());
        ordered.addAll(brand);
        ordered.addAll(others);
        return ordered;
    }

    /** Round-robin over the ordered servers; each server contributes at most {@code cap} tools. */
    private List<ScoredTool> diversify(List<ServerMatches> ordered, int limit, int cap) {
        var picks = new ArrayList<ScoredTool>();
        for (int rank = 0; rank < cap && picks.size() < limit; rank++) {
            boolean added = false;
            for (var server : ordered) {
                var tools = server.matched();
                if (rank >= tools.size()) continue;
                picks.add(tools.get(rank));
                added = true;
                if (picks.size() >= limit) break;
            }
            if (!added || picks.size() >= limit) break;
        }
        return picks;
    }

    public CatalogTool findTool(String serverName, String toolName) {
        var entry = entryByName(serverName);
        if (entry == null) return null;
        var snapshot = ensureLoaded(entry);
        for (var tool : staleMarkedTools(snapshot)) {
            if (tool.name().equals(toolName)) return tool;
        }
        return null;
    }

    /** Enabled MCP entry by its unique name, or null. */
    public ToolRegistryEntry entryByName(String serverName) {
        for (var entry : enabledMcpEntries()) {
            if (entry.name.equals(serverName)) return entry;
        }
        return null;
    }

    private ServerSnapshot ensureLoaded(ToolRegistryEntry entry) {
        var current = snapshots.get(entry.id);
        if (current != null) return current;
        synchronized (this) {
            current = snapshots.get(entry.id);
            if (current != null) return current;
            var snapshot = loadSnapshot(entry, null);
            snapshots.put(entry.id, snapshot);
            return snapshot;
        }
    }

    private ServerSnapshot loadSnapshot(ToolRegistryEntry entry, ServerSnapshot previous) {
        List<McpSchema.Tool> details;
        try {
            details = toolRegistryService.listMcpServerToolDetails(entry.id);
        } catch (RuntimeException e) {
            LOGGER.debug("catalog failed to list tools, server={}, reason={}", entry.name, e.getMessage());
            details = List.of();
        }
        var state = toolRegistryService.getMcpServerState(entry.id);
        if (details.isEmpty() && unavailable(state)) {
            if (previous != null) {
                return new ServerSnapshot(entry, previous.tools(), previous.state(), true);
            }
            return new ServerSnapshot(entry, List.of(), state, true);
        }
        var tools = details.stream()
                .map(tool -> new CatalogTool(entry.id, entry.name, tool.name(), tool.description(),
                        tool.inputSchema() != null ? JsonUtil.toJsonNotOnlyPublic(tool.inputSchema()) : null, false))
                .sorted(Comparator.comparing(CatalogTool::name))
                .toList();
        return new ServerSnapshot(entry, tools, state, false);
    }

    private List<CatalogTool> staleMarkedTools(ServerSnapshot snapshot) {
        if (!snapshot.stale()) return snapshot.tools();
        return snapshot.tools().stream().map(tool -> tool.withStale(true)).toList();
    }

    private List<ToolRegistryEntry> enabledMcpEntries() {
        return toolRegistryService.listTools(null).stream()
                .filter(entry -> entry.type == ToolType.MCP && Boolean.TRUE.equals(entry.enabled))
                .toList();
    }

    private boolean unavailable(McpClientManager.ConnectionState state) {
        return state == McpClientManager.ConnectionState.FAILED
                || state == McpClientManager.ConnectionState.RECONNECTING
                || state == McpClientManager.ConnectionState.CONNECTING;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    public record ServerViewData(ToolRegistryEntry entry, String state, int toolCount, boolean stale) {
    }

    public record CatalogTool(String serverId, String serverName, String name, String description,
                              String inputSchemaJson, boolean stale) {
        public String qualifiedName() {
            return serverName + "/" + name;
        }

        public String refId() {
            return "mcp-tool:" + serverId + ":" + name;
        }

        public CatalogTool withStale(boolean value) {
            return new CatalogTool(serverId, serverName, name, description, inputSchemaJson, value);
        }
    }

    public record ScoredTool(CatalogTool tool, int score) {
    }

    public record ServerSearchHit(String name, int serverScore, int matchedCount, String state, boolean stale) {
    }

    public record SearchOutcome(List<ServerSearchHit> servers, List<ScoredTool> tools) {
    }

    private record ServerMatches(ServerSnapshot snapshot, int serverScore, List<ScoredTool> matched) {
    }

    private record ServerSnapshot(ToolRegistryEntry entry, List<CatalogTool> tools,
                                  McpClientManager.ConnectionState state, boolean stale) {
    }
}
