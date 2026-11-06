package ai.core.cli.tool;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * The local counterpart of the sandbox's builtin tools: what a skill script calls through
 * {@code core_ai_session}'s {@code builtin} namespace when no {@code CORE_AI_HUB} is set.
 *
 * @author stephen
 */
@Command(name = "tool", description = "Discover and call the builtin tools this machine can run locally",
        subcommands = {ToolListCommand.class, ToolDescribeCommand.class, ToolCallCommand.class, ToolTaskCommand.class})
public class ToolCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        ConsoleWriter.println("Run 'core-ai-cli tool <subcommand> --help' for details.");
        return 0;
    }
}
