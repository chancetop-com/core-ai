package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prints a paste-ready guide that lets any shell-capable agent (Claude Code, Codex,
 * CI scripts) discover and call company MCP tools through {@code core-ai-cli mcp}.
 *
 * @author stephen
 */
@Command(name = "instructions", description = "Print a paste-ready guide for other agents to use core-ai-cli mcp and skill")
class McpInstructionsCommand extends HubCommandBase {
    @Option(names = "--format", defaultValue = "md", description = "Heading style: claude | codex | md")
    String format;

    @Override
    protected Integer execute() {
        ConsoleWriter.print(HubInstructions.merged(format));
        return HubExitCodes.SUCCESS;
    }
}
