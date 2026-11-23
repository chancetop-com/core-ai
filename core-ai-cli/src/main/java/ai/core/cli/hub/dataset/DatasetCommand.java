package ai.core.cli.hub.dataset;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli dataset} — read and write dataset data. A session ({@code --session} or
 * {@code CORE_AI_SESSION_ID}) narrows every command to that session's agent bindings, so a script never sees more
 * than the agent it runs for. Without one, the commands that address a dataset directly work on the catalog and on
 * GENERAL-dataset data, authorized by the caller's own dataset permissions; SESSION state stays session-only.
 *
 * @author stephen
 */
@Command(name = "dataset", description = "Read and write dataset data, with or without a session",
        subcommands = {DatasetListCommand.class, DatasetShowCommand.class, DatasetStateCommand.class,
            DatasetRecordsCommand.class, DatasetRenameFieldCommand.class})
public class DatasetCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli dataset <list|show|state|records|rename-field> [options]");
        ConsoleWriter.println("  list                                                     session bindings, or the catalog without a session");
        ConsoleWriter.println("  show <dataset>                                           type, permission, schema");
        ConsoleWriter.println("  state get|set|patch <dataset> [--data <json>|--data-file -]   session only");
        ConsoleWriter.println("  records query|insert|update|delete <dataset> [options]    session bindings, or GENERAL data");
        ConsoleWriter.println("  rename-field <dataset> --from <field> --to <field>       session bindings, or GENERAL data");
        ConsoleWriter.println("Without --session/CORE_AI_SESSION_ID: the catalog and GENERAL data, under your own dataset permissions.");
        ConsoleWriter.println("Run 'core-ai-cli dataset <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
