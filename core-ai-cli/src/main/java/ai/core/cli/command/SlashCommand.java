package ai.core.cli.command;

import java.util.List;

/**
 * @author xander
 */
public record SlashCommand(String name, String description, List<String> subCommands) {

    public SlashCommand(String name, String description) {
        this(name, description, List.of());
    }
}
