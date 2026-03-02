package ai.core.cli.command;

import java.util.List;

/**
 * @author xander
 */
public final class SlashCommandRegistry {

    private static final List<SlashCommand> COMMANDS = List.of(
            new SlashCommand("/help", "Show available commands"),
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
