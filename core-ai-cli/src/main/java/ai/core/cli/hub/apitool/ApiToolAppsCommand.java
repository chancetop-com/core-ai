package ai.core.cli.hub.apitool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;

/**
 * @author stephen
 */
@Command(name = "apps", description = "List Service API apps visible to the current user")
class ApiToolAppsCommand extends HubCommandBase {
    @Override
    protected Integer execute() {
        var response = apiToolClient().apps();
        if (json()) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.apiAppsText(response.apps));
        }
        return HubExitCodes.SUCCESS;
    }
}
