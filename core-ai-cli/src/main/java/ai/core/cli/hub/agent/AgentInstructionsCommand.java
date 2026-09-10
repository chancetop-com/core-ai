package ai.core.cli.hub.agent;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubInstructions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prints the guide that teaches a shell-capable agent (Claude Code, Codex, CI scripts) how to
 * delegate work to server-side agents through {@code core-ai-cli agent}.
 *
 * @author stephen
 */
@Command(name = "instructions", description = "Print a paste-ready guide for delegating work to agents")
class AgentInstructionsCommand extends HubCommandBase {
    @Option(names = "--format", defaultValue = "md", description = "Heading style: claude | codex | md")
    String format;

    @Override
    protected Integer execute() {
        ConsoleWriter.print(HubInstructions.merged(format));
        return HubExitCodes.SUCCESS;
    }
}
