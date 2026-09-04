package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;

/**
 * @author stephen
 */
@Command(name = "servers", description = "List MCP servers visible to the current user")
class McpServersCommand extends HubCommandBase {
    @Override
    protected Integer execute() {
        var response = client().servers();
        if (options.json) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.serversText(response.servers));
        }
        return HubExitCodes.SUCCESS;
    }
}
