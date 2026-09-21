package ai.core.cli.hub.dataset;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli dataset} — read and write the datasets bound to one session. The session is the boundary: every
 * operation names a session ({@code --session} or {@code CORE_AI_SESSION_ID}) and only reaches the datasets that
 * session's agent was granted, so a script never sees more than the agent it runs for.
 *
 * @author stephen
 */
@Command(name = "dataset", description = "Read and write the datasets bound to a session",
        subcommands = {DatasetListCommand.class, DatasetShowCommand.class, DatasetStateCommand.class,
            DatasetRecordsCommand.class})
public class DatasetCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli dataset <list|show|state|records> [options]");
        ConsoleWriter.println("  list                                                     datasets bound to a session");
        ConsoleWriter.println("  show <dataset>                                           one binding: type, permission, schema");
        ConsoleWriter.println("  state get|set|patch <dataset> [--data <json>|--data-file -]");
        ConsoleWriter.println("  records query|insert|update|delete <dataset> [options]");
        ConsoleWriter.println("Run 'core-ai-cli dataset <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
