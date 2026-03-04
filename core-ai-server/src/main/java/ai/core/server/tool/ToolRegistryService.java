package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.mcp.client.McpServerConfig;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.ToolType;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class ToolRegistryService {
    private static final Map<String, List<ToolCall>> BUILTIN_TOOL_SETS = Map.of(
        "builtin-all", BuiltinTools.ALL,
        "builtin-file-operations", BuiltinTools.FILE_OPERATIONS,
        "builtin-file-read-only", BuiltinTools.FILE_READ_ONLY,
        "builtin-multimodal", BuiltinTools.MULTIMODAL,
        "builtin-web", BuiltinTools.WEB,
        "builtin-code-execution", BuiltinTools.CODE_EXECUTION
    );

    private final Map<String, ToolRegistry> tools = new ConcurrentHashMap<>();

    @Inject
    MongoCollection<ToolRegistry> toolRegistryCollection;

    public void initialize() {
        var entries = toolRegistryCollection.find(Filters.eq("enabled", true));
        for (var entry : entries) {
            tools.put(entry.id, entry);
            if (entry.type == ToolType.MCP) {
                registerMcpServer(entry);
            }
        }
    }

    public List<ToolRegistry> listTools(String category) {
        if (category != null && !category.isBlank()) {
            return tools.values().stream()
                .filter(t -> category.equals(t.category))
                .toList();
        }
        return List.copyOf(tools.values());
    }

    public ToolRegistry getTool(String id) {
        var tool = tools.get(id);
        if (tool == null) throw new RuntimeException("tool not found, id=" + id);
        return tool;
    }

    public List<String> listCategories() {
        return tools.values().stream()
            .map(t -> t.category)
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    public List<ToolCall> resolveTools(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) return List.of();

        var result = new ArrayList<ToolCall>();
        for (var toolId : toolIds) {
            var builtinSet = BUILTIN_TOOL_SETS.get(toolId);
            if (builtinSet != null) {
                result.addAll(builtinSet);
                continue;
            }

            var entry = tools.get(toolId);
            if (entry == null) continue;

            switch (entry.type) {
                case MCP -> {
                    var mcpManager = McpClientManagerRegistry.getManager();
                    if (mcpManager != null && mcpManager.hasServer(entry.id)) {
                        result.addAll(McpToolCalls.from(mcpManager, List.of(entry.id), null));
                    }
                }
                case BUILTIN -> {
                    var set = BUILTIN_TOOL_SETS.get(entry.config.get("set"));
                    if (set != null) result.addAll(set);
                }
                case API -> {
                    // TODO: implement API tool resolution
                }
                default -> { }
            }
        }
        return result;
    }

    private void registerMcpServer(ToolRegistry entry) {
        var mcpManager = McpClientManagerRegistry.getManager();
        if (mcpManager == null) {
            mcpManager = new McpClientManager();
            McpClientManagerRegistry.setManager(mcpManager);
        }

        if (mcpManager.hasServer(entry.id)) return;

        var configMap = new HashMap<String, Object>(entry.config);
        var serverConfig = McpServerConfig.fromMap(entry.id, configMap);
        mcpManager.addServer(serverConfig);
    }
}
