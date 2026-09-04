package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubInstructions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * @author stephen
 */
@Command(name = "instructions", description = "Print a paste-ready guide for other agents to use core-ai-cli skill (and mcp)")
class SkillInstructionsCommand extends HubCommandBase {
    @Option(names = "--format", defaultValue = "md", description = "Heading style: claude | codex | md")
    String format;

    @Override
    protected Integer execute() {
        ConsoleWriter.print(HubInstructions.merged(format));
        return HubExitCodes.SUCCESS;
    }
}
