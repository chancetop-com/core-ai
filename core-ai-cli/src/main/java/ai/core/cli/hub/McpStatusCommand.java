package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "status", description = "Show server connection status and tool counts")
class McpStatusCommand extends HubCommandBase {
    @Parameters(index = "0", arity = "0..1", paramLabel = "server", description = "Optional server name to inspect")
    String serverName;

    @Override
    protected Integer execute() {
        var response = client().servers();
        if (serverName != null && !serverName.isBlank()) {
            var matches = response.servers.stream().filter(server -> serverName.equals(server.name)).toList();
            if (matches.isEmpty()) {
                throw new HubCliError(HubExitCodes.NOT_FOUND, "mcp server not found or not visible: " + serverName);
            }
            response.servers = matches;
        }
        if (options.json) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.serversText(response.servers));
        }
        return HubExitCodes.SUCCESS;
    }
}
