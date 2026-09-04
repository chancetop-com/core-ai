package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Locale;
import java.util.Set;

/**
 * @author stephen
 */
@Command(name = "search", description = "Search the skill catalog (query omitted: list all)")
class SkillSearchCommand extends HubCommandBase {
    private static final Set<String> SOURCE_TYPES = Set.of("upload", "repo");

    @Parameters(index = "0", arity = "0..1", paramLabel = "query", description = "Free-text search terms")
    String query;

    @Option(names = "--namespace", description = "Only search skills of this namespace")
    String namespace;

    @Option(names = "--source", description = "Only search skills of this source type (upload | repo)")
    String sourceType;

    @Option(names = "--limit", description = "Max results (default 20, max 200)")
    Integer limit;

    @Override
    protected Integer execute() {
        if (sourceType != null && !sourceType.isBlank() && !SOURCE_TYPES.contains(sourceType.toLowerCase(Locale.ROOT))) {
            throw new HubCliError(HubExitCodes.USAGE,
                    "invalid --source '" + sourceType + "', expected upload or repo");
        }
        var response = skillClient().search(query, namespace, sourceType, limit);
        if (json()) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.skillSearchText(response));
        }
        return HubExitCodes.SUCCESS;
    }
}
