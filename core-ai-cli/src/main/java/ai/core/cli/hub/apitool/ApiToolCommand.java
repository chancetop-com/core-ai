package ai.core.cli.hub.apitool;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli api-tool} — search, inspect and call Service API operations
 * registered on core-ai-server without starting an agent session.
 *
 * @author stephen
 */
@Command(name = "api-tool", description = "Search and call Service API operations registered on core-ai-server",
        subcommands = {ApiToolAppsCommand.class, ApiToolSearchCommand.class, ApiToolDescribeCommand.class,
            ApiToolCallCommand.class, ApiToolInstructionsCommand.class})
public class ApiToolCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli api-tool <apps|search|describe|call|instructions> [options]");
        ConsoleWriter.println("Run 'core-ai-cli api-tool <subcommand> --help' for details.");
        return ai.core.cli.hub.HubExitCodes.USAGE;
    }
}
