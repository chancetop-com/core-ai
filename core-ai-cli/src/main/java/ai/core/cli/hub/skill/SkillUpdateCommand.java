package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Updates hub-installed skills to the current server content. Only {@code outdated}
 * skills are touched; {@code modified} ones (local files drifted from the pulled
 * digest) are skipped unless {@code --force}. {@code --all} walks every hub-marked
 * skill in the target root; named references may be qualified or bare. The update
 * mechanics live in {@link SkillUpdater}, shared with the desktop RPC.
 *
 * @author stephen
 */
@Command(name = "update", description = "Update pulled skills to the current server content")
class SkillUpdateCommand extends HubCommandBase {
    @Parameters(arity = "0..*", paramLabel = "ns/name", description = "Skills to update (omit with --all)")
    List<String> skills;

    @Option(names = "--all", description = "Update every hub-installed skill in the target root")
    boolean all;

    @Option(names = "--workspace", description = "Target {cwd}/.core-ai/skills instead of the user-level skills")
    boolean workspace;

    @Option(names = "--force", description = "Also overwrite locally modified skills")
    boolean force;

    @Override
    protected Integer execute() {
        if ((skills == null || skills.isEmpty()) && !all) {
            throw new HubCliError(HubExitCodes.USAGE,
                    "specify skills to update, or pass --all to update every hub-installed skill");
        }
        var client = skillClient();
        var baseDir = workspace ? SkillLocations.workspaceSkillsDir() : SkillLocations.userSkillsDir();
        var updater = new SkillUpdater();
        List<SkillUpdater.Outcome> outcomes;
        if (all) {
            outcomes = updater.updateAll(client, baseDir, force);
        } else {
            var resolved = new ArrayList<SkillUpdater.Outcome>();
            var resolver = new SkillNameResolver();
            for (var reference : skills) {
                var qualified = resolver.resolve(client, reference);
                resolved.addAll(updater.update(client, baseDir, qualified.qualifiedName(), force));
            }
            outcomes = resolved;
        }
        if (outcomes.isEmpty()) {
            ConsoleWriter.println("  (no hub-installed skills to update)");
            return HubExitCodes.SUCCESS;
        }
        printSummary(outcomes);
        return HubExitCodes.SUCCESS;
    }

    private void printSummary(List<SkillUpdater.Outcome> outcomes) {
        var updated = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        for (var outcome : outcomes) {
            if (outcome.updated()) {
                updated.add(outcome.qualifiedName());
            } else {
                skipped.add(outcome.qualifiedName() + " " + outcome.reason());
            }
        }
        if (json()) {
            var json = new LinkedHashMap<String, Object>();
            json.put("updated", updated);
            json.put("skipped", skipped);
            HubRenderer.printJson(json);
        } else {
            for (var name : updated) ConsoleWriter.println("Updated " + name);
            for (var reason : skipped) ConsoleWriter.println("Skipped " + reason);
            if (updated.isEmpty()) ConsoleWriter.println("  (nothing to update)");
        }
    }
}
