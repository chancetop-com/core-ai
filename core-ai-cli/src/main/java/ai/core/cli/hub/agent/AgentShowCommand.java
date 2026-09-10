package ai.core.cli.hub.agent;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "show", description = "Show one agent: what it does, its type and its input hint")
class AgentShowCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "id|name", description = "Agent id, or a name unique in your catalog")
    String idOrName;

    @Override
    protected Integer execute() {
        var client = agentClient();
        var detail = client.show(new AgentNameResolver(client).resolve(idOrName));
        if (json()) {
            HubRenderer.printJson(detail);
        } else {
            ConsoleWriter.print(new RunResultRenderer().detailText(detail));
        }
        return HubExitCodes.SUCCESS;
    }
}
