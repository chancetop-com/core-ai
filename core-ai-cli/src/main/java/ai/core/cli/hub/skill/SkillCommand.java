package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli skill} — discover, read and install skills registered on
 * core-ai-server without starting an agent session.
 *
 * @author stephen
 */
@Command(name = "skill", description = "Discover, read and install skills registered on core-ai-server",
        subcommands = {SkillSearchCommand.class, SkillShowCommand.class, SkillPullCommand.class,
            SkillListCommand.class, SkillUpdateCommand.class, SkillRemoveCommand.class,
            SkillPushCommand.class, SkillInstructionsCommand.class})
public class SkillCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli skill <search|show|pull|list|update|remove|push|instructions> [options]");
        ConsoleWriter.println("Run 'core-ai-cli skill <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
