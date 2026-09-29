package ai.core.cli.hub.catalog;

import ai.core.api.server.hubcatalog.HubCatalogTool;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * The whole hub catalog in one request: every MCP tool, Service API operation and agent / LLM_CALL
 * definition the account can call. It is what {@code core_ai_session}'s local transport enumerates
 * from, so a local script pays one round trip instead of one listing per server and per app.
 *
 * @author stephen
 */
@Command(name = "catalog", description = "Fetch the whole hub catalog in one request (MCP tools, API operations, agents)")
public class CatalogCommand extends HubCommandBase {
    @Option(names = "--kind", description = "Only this kind: mcp | api | agent | llm_call")
    String kind;

    @Override
    protected Integer execute() {
        var response = catalogClient().catalog();
        if (kind != null && !kind.isBlank()) {
            var tools = response.tools == null ? List.<HubCatalogTool>of() : response.tools;
            response.tools = tools.stream().filter(tool -> kind.equalsIgnoreCase(tool.kind)).toList();
            if (response.sources != null) {
                response.sources = response.sources.stream()
                        .filter(source -> kind.equalsIgnoreCase(source.kind))
                        .toList();
            }
        }
        if (json()) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.catalogText(response));
        }
        return HubExitCodes.SUCCESS;
    }
}
