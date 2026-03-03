package ai.core.cli.command;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;

/**
 * Handles /mcp command to list MCP server status.
 *
 * @author xander
 */
public class McpCommandHandler {

    private final TerminalUI ui;

    public McpCommandHandler(TerminalUI ui) {
        this.ui = ui;
    }

    public void handle() {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No MCP servers configured.\n" + AnsiTheme.RESET);
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Add mcp.servers to agent.properties.\n" + AnsiTheme.RESET);
            return;
        }
        var serverNames = manager.getServerNames();
        if (serverNames == null || serverNames.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No MCP servers configured.\n" + AnsiTheme.RESET);
            return;
        }
        ui.printStreamingChunk(String.format("%n  %sMCP Servers (%d)%s%n", AnsiTheme.PROMPT, serverNames.size(), AnsiTheme.RESET));
        for (var name : serverNames) {
            String status = getServerStatus(manager, name);
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + name + AnsiTheme.RESET + status + "\n");
        }
        ui.printStreamingChunk("\n");
    }

    private String getServerStatus(McpClientManager manager, String name) {
        try {
            var client = manager.getClient(name);
            if (client == null) return AnsiTheme.ERROR + " (not found)" + AnsiTheme.RESET;
            int toolCount = client.listTools().size();
            return AnsiTheme.SUCCESS + " ✓" + AnsiTheme.RESET + AnsiTheme.MUTED + " (" + toolCount + " tools)" + AnsiTheme.RESET;
        } catch (Exception e) {
            return AnsiTheme.ERROR + " ✗ " + e.getMessage() + AnsiTheme.RESET;
        }
    }
}
