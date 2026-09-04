package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Locale;

/**
 * Prints a paste-ready guide that lets any shell-capable agent (Claude Code, Codex,
 * CI scripts) discover and call company MCP tools through {@code core-ai-cli mcp}.
 *
 * @author stephen
 */
@Command(name = "instructions", description = "Print a paste-ready guide for other agents to use core-ai-cli mcp")
class McpInstructionsCommand extends HubCommandBase {
    private static final String TEMPLATE = """
            %s
            You have access to internal tools via the `core-ai-cli mcp` command. Do NOT guess tool names.
            1. Discover:  core-ai-cli mcp search "<what you need>" --json
            2. Inspect:   core-ai-cli mcp describe <server>/<tool> --json      # read input_schema before calling
            3. Execute:   core-ai-cli mcp call <server>/<tool> --args '<json>' --json
            Exit code 0 = success; parse stdout as JSON. On 4 (permission) stop and tell the user.
            """;

    @Option(names = "--format", defaultValue = "md", description = "Heading style: claude | codex | md")
    String format;

    @Override
    protected Integer execute() {
        var heading = switch (format.toLowerCase(Locale.ROOT)) {
            case "claude" -> "## Company tools (core-ai MCP Hub)  # paste into CLAUDE.md";
            case "codex" -> "## Company tools (core-ai MCP Hub)  # paste into AGENTS.md";
            default -> "## Company tools (core-ai MCP Hub)";
        };
        ConsoleWriter.print(TEMPLATE.replace("%s", heading));
        return HubExitCodes.SUCCESS;
    }
}
