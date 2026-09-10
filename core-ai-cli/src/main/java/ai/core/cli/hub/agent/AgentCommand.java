package ai.core.cli.hub.agent;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli agent} — find an agent registered on core-ai-server and run it as a task,
 * without starting a local agent session. Execution is server-side: the run is an A2A task whose
 * {@code context_id} continues the conversation and whose {@code input_required} state asks for a
 * tool approval.
 *
 * @author stephen
 */
@Command(name = "agent", description = "Search and run agents registered on core-ai-server",
        subcommands = {AgentSearchCommand.class, AgentShowCommand.class, AgentRunCommand.class,
            AgentStatusCommand.class, AgentReplyCommand.class, AgentCancelCommand.class,
            AgentInstructionsCommand.class})
public class AgentCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli agent <search|show|run|status|reply|cancel|instructions> [options]");
        ConsoleWriter.println("Run 'core-ai-cli agent <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
