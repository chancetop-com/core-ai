package ai.core.cli.hub.dataset;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * @author stephen
 */
@Command(name = "state", description = "Read and write session state (SESSION datasets)",
        subcommands = {DatasetStateGetCommand.class, DatasetStateSetCommand.class, DatasetStatePatchCommand.class})
public class DatasetStateCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli dataset state <get|set|patch> <dataset> [options]");
        ConsoleWriter.println("Run 'core-ai-cli dataset state <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
