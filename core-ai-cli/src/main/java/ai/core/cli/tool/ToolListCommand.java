package ai.core.cli.tool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
@Command(name = "list", description = "List the builtin tools this machine can call locally")
class ToolListCommand extends ToolCommandBase {
    private static Map<String, Object> summary(LocalToolRuntime.LocalBuiltinTool builtin) {
        var item = new LinkedHashMap<String, Object>();
        item.put("name", builtin.name());
        item.put("kind", LocalToolRuntime.KIND_BUILTIN);
        item.put("group", "");
        item.put("path", builtin.name());
        item.put("ref_id", builtin.name());
        item.put("description", builtin.description());
        item.put("input_schema", builtin.inputSchema());
        item.put("timeout_seconds", builtin.timeoutSeconds());
        return item;
    }

    @Override
    protected Integer execute() {
        var tools = runtime().tools();
        if (json()) {
            var items = new ArrayList<Map<String, Object>>(tools.size());
            for (var tool : tools) items.add(summary(tool));
            HubRenderer.printJson(Map.of("tools", items));
            return HubExitCodes.SUCCESS;
        }
        if (tools.isEmpty()) {
            ConsoleWriter.println("  (no local builtin tools configured)");
            return HubExitCodes.SUCCESS;
        }
        for (var tool : tools) {
            ConsoleWriter.println("  " + tool.name() + "  (" + tool.timeoutSeconds() + "s)");
            if (tool.description() != null && !tool.description().isBlank()) {
                ConsoleWriter.println("    " + tool.description().lines().findFirst().orElse(""));
            }
        }
        return HubExitCodes.SUCCESS;
    }
}
