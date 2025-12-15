package ai.core.agent.slashcommand;

/**
 * @author stephen
 */
public class SlashCommandParser {
    public static final String SLASH_COMMAND_PREFIX = "/slash_command:";

    public static boolean isSlashCommand(String query) {
        return query != null && query.startsWith(SLASH_COMMAND_PREFIX);
    }

    public static SlashCommandResult parse(String query) {
        if (!isSlashCommand(query)) {
            return SlashCommandResult.invalid(query);
        }

        // Remove prefix
        var remaining = query.substring(SLASH_COMMAND_PREFIX.length());

        if (remaining.isEmpty()) {
            return SlashCommandResult.invalid(query);
        }

        // Find the first colon to separate tool name from arguments
        var colonIndex = remaining.indexOf(':');

        String toolName;
        String arguments;

        if (colonIndex == -1) {
            // No arguments provided
            toolName = remaining.trim();
            arguments = null;
        } else if (colonIndex == 0) {
            // Empty tool name
            return SlashCommandResult.invalid(query);
        } else {
            toolName = remaining.substring(0, colonIndex).trim();
            var argsString = remaining.substring(colonIndex + 1);
            arguments = argsString.isEmpty() ? null : argsString;
        }

        if (toolName.isEmpty()) {
            return SlashCommandResult.invalid(query);
        }

        return SlashCommandResult.valid(query, toolName, arguments);
    }
}
