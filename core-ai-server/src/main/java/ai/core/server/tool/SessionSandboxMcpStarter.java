package ai.core.server.tool;

import ai.core.mcp.client.McpClientManager;
import ai.core.sandbox.Sandbox;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Starts the sandbox-hosted MCP servers a session's tool refs point at. Tool refs are resolved
 * while the session's agent is built, and a cold {@code npx} start can take minutes: a chat
 * session passes {@code backgroundStart=true} so the build returns immediately and the tool
 * provider picks the server up once it is registered.
 *
 * @author core-ai
 */
class SessionSandboxMcpStarter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionSandboxMcpStarter.class);

    private final Map<String, ToolRegistryEntry> tools;
    private final McpResolutionDependencies mcpDependencies;

    SessionSandboxMcpStarter(Map<String, ToolRegistryEntry> tools, McpResolutionDependencies mcpDependencies) {
        this.tools = tools;
        this.mcpDependencies = mcpDependencies;
    }

    McpClientManager prepare(List<ToolRef> toolRefs, String sessionId, Sandbox sandbox, boolean backgroundStart) {
        var sandboxHostedEntries = collectSandboxHostedEntries(toolRefs);
        if (sandboxHostedEntries.isEmpty()) return null;

        if (mcpDependencies.sandboxService() != null) mcpDependencies.sandboxService().ensureSandboxReady(sessionId);

        var sessionMgr = mcpDependencies.sandboxService() != null ? mcpDependencies.sandboxService().sessionMcpProcesses().managerFor(sessionId) : null;
        if (sessionMgr == null) return null;

        for (var entry : sandboxHostedEntries) {
            try {
                mcpDependencies.connectionManager().registerOnSession(entry, sessionMgr, sandbox, sessionId, backgroundStart);
            } catch (Exception e) {
                LOGGER.warn("failed to start sandbox-hosted mcp server during session creation, id={}, name={}: {}",
                        entry.id, entry.name, e.getMessage());
            }
        }

        return sessionMgr;
    }

    private List<ToolRegistryEntry> collectSandboxHostedEntries(List<ToolRef> toolRefs) {
        var seen = new LinkedHashSet<String>();
        var result = new ArrayList<ToolRegistryEntry>();
        for (var ref : toolRefs) {
            if (ref == null || ref.id == null) continue;
            var entry = findMcpEntryForRef(ref);
            if (entry != null && Boolean.TRUE.equals(entry.enabled)
                    && "sandbox_hosted".equalsIgnoreCase(entry.config.get("transport")) && seen.add(entry.id)) {
                result.add(entry);
            }
        }
        return result;
    }

    private ToolRegistryEntry findMcpEntryForRef(ToolRef ref) {
        var entry = tools.get(ref.id);
        if (entry != null && entry.type == ToolType.MCP) return entry;
        var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (parsed != null && parsed.serverId() != null) {
            var serverEntry = tools.get(parsed.serverId());
            if (serverEntry != null && serverEntry.type == ToolType.MCP) return serverEntry;
        }
        if (ref.source != null) {
            var srcEntry = tools.get(ref.source);
            if (srcEntry != null && srcEntry.type == ToolType.MCP) return srcEntry;
        }
        return null;
    }
}
