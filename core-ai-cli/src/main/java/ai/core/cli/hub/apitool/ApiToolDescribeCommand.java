package ai.core.cli.hub.apitool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "describe", description = "Show one operation's detail and input schema (app/service/operation or app_service_operation)")
class ApiToolDescribeCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "app/service/operation", description = "Qualified operation name, e.g. order-service/refund/create")
    String qualified;

    @Override
    protected Integer execute() {
        var client = apiToolClient();
        var parts = ApiToolNameParser.resolve(client, qualified);
        var detail = client.describe(parts[0], parts[1], parts[2]);
        if (json()) {
            HubRenderer.printJson(detail);
        } else {
            ConsoleWriter.print(renderer.apiDetailText(detail));
        }
        return HubExitCodes.SUCCESS;
    }
}
