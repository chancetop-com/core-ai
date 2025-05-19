package ai.core.mcp.server;

import ai.core.tool.ToolCall;

import java.util.List;

/**
 * @author stephen
 */
public interface McpServerToolLoader {
    List<ToolCall> load();
}
