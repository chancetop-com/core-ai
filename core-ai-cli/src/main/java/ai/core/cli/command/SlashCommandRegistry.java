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
            new SlashCommand("/resume", "Switch to a previous session"),
            new SlashCommand("/debug", "Toggle debug mode"),
            new SlashCommand("/clear", "Clear screen"),
            new SlashCommand("/exit", "Quit")
    );

    public static List<SlashCommand> all() {
        return COMMANDS;
    }

    private SlashCommandRegistry() {
    }
}
