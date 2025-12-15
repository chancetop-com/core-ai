package ai.core.agent.slashcommand;

/**
 * @author stephen
 */
public final class SlashCommandResult {
    public static SlashCommandResult valid(String originalQuery, String toolName, String arguments) {
        return new SlashCommandResult(originalQuery, toolName, arguments, true);
    }

    public static SlashCommandResult invalid(String originalQuery) {
        return new SlashCommandResult(originalQuery, null, null, false);
    }

    private final String originalQuery;
    private final String toolName;
    private final String arguments;
    private final boolean valid;

    private SlashCommandResult(String originalQuery, String toolName, String arguments, boolean valid) {
        this.originalQuery = originalQuery;
        this.toolName = toolName;
        this.arguments = arguments;
        this.valid = valid;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public boolean isNotValid() {
        return !valid;
    }

    public boolean hasArguments() {
        return arguments != null && !arguments.isEmpty();
    }

    @Override
    public String toString() {
        return "SlashCommandResult{originalQuery='"
                + originalQuery + "', toolName='"
                + toolName + "', arguments='"
                + arguments + "', valid="
                + valid + '}';
    }
}
