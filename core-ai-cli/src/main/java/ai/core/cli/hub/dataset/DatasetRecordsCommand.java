package ai.core.cli.hub.dataset;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * @author stephen
 */
@Command(name = "records", description = "Query and write records (GENERAL datasets)",
        subcommands = {DatasetRecordsQueryCommand.class, DatasetRecordsInsertCommand.class,
            DatasetRecordsUpdateCommand.class, DatasetRecordsDeleteCommand.class})
public class DatasetRecordsCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli dataset records <query|insert|update|delete> <dataset> [options]");
        ConsoleWriter.println("Run 'core-ai-cli dataset records <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
