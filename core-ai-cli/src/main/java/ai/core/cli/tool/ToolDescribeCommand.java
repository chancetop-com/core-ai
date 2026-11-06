package ai.core.cli.tool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;

/**
 * @author stephen
 */
@Command(name = "describe", description = "Show one local builtin tool's description and input schema")
class ToolDescribeCommand extends ToolCommandBase {
    @Parameters(index = "0", paramLabel = "name", description = "Builtin tool name, e.g. generate_image")
    String name;

    @Override
    protected Integer execute() {
        var builtin = runtime().tool(name).orElseThrow(() -> new HubCliError(
                HubExitCodes.NOT_FOUND, "unknown builtin tool: " + name + " (run 'core-ai-cli tool list')"));
        if (json()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("name", builtin.name());
            payload.put("kind", LocalToolRuntime.KIND_BUILTIN);
            payload.put("group", "");
            payload.put("path", builtin.name());
            payload.put("ref_id", builtin.name());
            payload.put("description", builtin.description());
            payload.put("input_schema", builtin.inputSchema());
            payload.put("timeout_seconds", builtin.timeoutSeconds());
            HubRenderer.printJson(payload);
        } else {
            ConsoleWriter.println(builtin.description());
            ConsoleWriter.println();
            ConsoleWriter.println(builtin.inputSchema());
        }
        return HubExitCodes.SUCCESS;
    }
}
