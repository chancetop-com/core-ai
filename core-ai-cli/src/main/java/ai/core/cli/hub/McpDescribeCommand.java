package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "describe", description = "Show one tool's detail and input schema (server/tool)")
class McpDescribeCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "server/tool", description = "Qualified tool name, e.g. jira/create_issue")
    String qualified;

    @Override
    protected Integer execute() {
        var parts = qualified.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new HubCliError(HubExitCodes.USAGE,
                    "expected <server>/<tool>, got: " + qualified);
        }
        var detail = client().describe(parts[0], parts[1]);
        if (options.json) {
            HubRenderer.printJson(detail);
        } else {
            ConsoleWriter.print(renderer.detailText(detail));
        }
        return HubExitCodes.SUCCESS;
    }
}
