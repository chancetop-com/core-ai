package ai.core.cli.hub.agent;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "search", description = "Search the agent catalog visible to you (query omitted: list all)")
class AgentSearchCommand extends HubCommandBase {
    @Parameters(index = "0", arity = "0..1", paramLabel = "query", description = "Free-text search terms")
    String query;

    @Option(names = "--type", description = "Only agents of this type: agent | llm_call")
    String type;

    @Option(names = "--source", description = "Only agents from this source: server | external")
    String source;

    @Option(names = "--limit", description = "Max results (default 20, max 200)")
    Integer limit;

    @Override
    protected Integer execute() {
        var response = agentClient().search(query, type, source, limit);
        if (json()) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(new RunResultRenderer().searchText(response.agents));
        }
        return HubExitCodes.SUCCESS;
    }
}
