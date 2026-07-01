package ai.core.cli.command;

import java.util.List;

/**
 * @author xander
 */
public final class SlashCommandRegistry {

    private static final List<SlashCommand> COMMANDS = List.of(
            new SlashCommand("/help", "Show available commands"),
            new SlashCommand("/model", "Show or switch model (/model <name>)"),
            new SlashCommand("/stats", "Show session statistics and token usage"),
            new SlashCommand("/tools", "List available tools"),
            new SlashCommand("/copy", "Copy last response to clipboard"),
            new SlashCommand("/compact", "Remove old messages to free context"),
            new SlashCommand("/export", "Export session to markdown (/export [file])"),
            new SlashCommand("/memory", "Manage memory files"),
            new SlashCommand("/memory edit", "Select a memory file to edit"),
            new SlashCommand("/memory search", "Search memories by keyword"),
            new SlashCommand("/memory open", "Open memory folder in file manager"),
            new SlashCommand("/memory clear", "Delete knowledge wiki pages, recreate structure"),
            new SlashCommand("/memory enable", "Enable memory (set agent.memory.enabled=true)"),
            new SlashCommand("/memory disable", "Disable memory (set agent.memory.enabled=false)"),
            new SlashCommand("/init", "Create .core-ai/instructions.md project config"),
            new SlashCommand("/skill", "List loaded skills"),
            new SlashCommand("/plugins", "Manage plugins (/plugins help for more)"),
            new SlashCommand("/mcp", "Show MCP server status"),
            new SlashCommand("/undo", "Undo last message and its response"),
            new SlashCommand("/login", "Authenticate with a core-ai-server"),
            new SlashCommand("/logout", "Log out of the current server"),
            new SlashCommand("/status", "Show auth status and user info"),
            new SlashCommand("/server", "List or switch registered servers"),
            new SlashCommand("/resume", "Switch to a previous session"),
            new SlashCommand("/debug", "Toggle debug mode"),
            new SlashCommand("/clear", "Start new session"),
            new SlashCommand("/exit", "Quit"),
            new SlashCommand("/upgrade", "Check for updates and upgrade CLI")
    );

    public static List<SlashCommand> all() {
        return COMMANDS;
    }

    private SlashCommandRegistry() {
    }
}
