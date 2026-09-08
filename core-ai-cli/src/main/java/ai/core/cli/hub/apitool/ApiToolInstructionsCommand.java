package ai.core.cli.hub.apitool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubInstructions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prints a paste-ready guide that lets any shell-capable agent (Claude Code, Codex,
 * CI scripts) discover and call company MCP tools, skills and API tools through
 * {@code core-ai-cli}.
 *
 * @author stephen
 */
@Command(name = "instructions", description = "Print a paste-ready guide for other agents to use core-ai-cli mcp, skill and api-tool")
class ApiToolInstructionsCommand extends HubCommandBase {
    @Option(names = "--format", defaultValue = "md", description = "Heading style: claude | codex | md")
    String format;

    @Override
    protected Integer execute() {
        ConsoleWriter.print(HubInstructions.merged(format));
        return HubExitCodes.SUCCESS;
    }
}
