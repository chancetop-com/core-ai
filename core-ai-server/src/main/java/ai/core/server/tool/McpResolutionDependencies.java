package ai.core.server.tool;

import ai.core.server.sandbox.SandboxService;

/**
 * MCP dependencies shared by tool reference resolution paths.
 *
 * @author Stephen
 */
record McpResolutionDependencies(McpServerConnectionManager connectionManager, SandboxService sandboxService,
                                ApplicationMcpManager applicationMcpManager) {
}
