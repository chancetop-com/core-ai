package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli mcp} — search and call MCP tools registered on core-ai-server
 * without starting an agent session.
 *
 * @author stephen
 */
@Command(name = "mcp", description = "Search and call MCP tools registered on core-ai-server",
        subcommands = {McpServersCommand.class, McpSearchCommand.class, McpDescribeCommand.class,
            McpCallCommand.class, McpStatusCommand.class, McpInstructionsCommand.class})
public class McpCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Override
    public Integer call() {
        ConsoleWriter.println("Usage: core-ai-cli mcp <servers|search|describe|call|status|instructions> [options]");
        ConsoleWriter.println("Run 'core-ai-cli mcp <subcommand> --help' for details.");
        return HubExitCodes.USAGE;
    }
}
