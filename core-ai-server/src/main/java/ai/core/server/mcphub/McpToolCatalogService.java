package ai.core.server.mcphub;

import ai.core.mcp.client.McpClientManager;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.tool.ToolRegistryService;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    public List<ScoredTool> search(String query, String serverFilter, Integer limit) {
        int effectiveLimit = normalizeLimit(limit);
        var tokens = query == null ? List.<String>of() : tokenize(query);
        var matches = new ArrayList<ScoredTool>();
        for (var entry : enabledMcpEntries()) {
            if (serverFilter != null && !serverFilter.isBlank() && !serverFilter.equals(entry.name)) continue;
            var snapshot = ensureLoaded(entry);
            for (var tool : staleMarkedTools(snapshot)) {
                if (tokens.isEmpty()) {
                    matches.add(new ScoredTool(tool, 0));
                } else {
                    int score = score(tool, query, tokens);
                    if (score > 0) matches.add(new ScoredTool(tool, score));
                }
            }
        }
        Comparator<ScoredTool> order = tokens.isEmpty()
                ? Comparator.comparing(scored -> scored.tool().qualifiedName())
                : Comparator.comparingInt(ScoredTool::score).reversed()
                        .thenComparing(scored -> scored.tool().qualifiedName());
        matches.sort(order);
        return matches.size() > effectiveLimit ? List.copyOf(matches.subList(0, effectiveLimit)) : matches;
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

    /**
     * Keyword scoring matching the semantics of agent-side tool activation search:
     * every token must hit at least one field or the tool is dropped.
     */
    private int score(CatalogTool tool, String query, List<String> tokens) {
        var name = tool.name().toLowerCase(Locale.ROOT);
        var server = tool.serverName().toLowerCase(Locale.ROOT);
        var qualified = tool.qualifiedName().toLowerCase(Locale.ROOT);
        var description = tool.description() == null ? "" : tool.description().toLowerCase(Locale.ROOT);
        int total = 0;
        if (tool.name().equalsIgnoreCase(query)) total += 100;
        for (var token : tokens) {
            boolean hit = false;
            if (name.contains(token)) {
                hit = true;
                total += 10;
            }
            if (server.contains(token)) {
                hit = true;
                total += 5;
            }
            if (qualified.startsWith(token + "/")) {
                hit = true;
                total += 3;
            }
            if (description.contains(token)) {
                hit = true;
                total += 2;
            }
            if (!hit) return 0;
        }
        return total;
    }

    private List<String> tokenize(String query) {
        var tokens = new ArrayList<String>();
        for (var part : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!part.isBlank()) tokens.add(part);
        }
        return tokens;
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

    private record ServerSnapshot(ToolRegistryEntry entry, List<CatalogTool> tools,
                                  McpClientManager.ConnectionState state, boolean stale) {
    }
}
