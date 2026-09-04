package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Updates hub-installed skills to the current server content. Only {@code outdated}
 * skills are touched; {@code modified} ones (local files drifted from the pulled
 * digest) are skipped unless {@code --force}. {@code --all} walks every hub-marked
 * skill in the target root; named references may be qualified or bare.
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
        var targets = collectTargets(client, baseDir);
        if (targets.isEmpty()) {
            ConsoleWriter.println("  (no hub-installed skills to update)");
            return HubExitCodes.SUCCESS;
        }
        var summary = updateTargets(client, baseDir, targets);
        printSummary(summary);
        return HubExitCodes.SUCCESS;
    }

    private List<UpdateTarget> collectTargets(SkillHubClient client, Path baseDir) {
        var scanner = new LocalSkillScanner();
        var targets = new ArrayList<UpdateTarget>();
        if (all) {
            for (var local : scanner.scan(baseDir)) {
                if (local.marker() != null && local.marker().isManaged()) {
                    targets.add(new UpdateTarget(markerName(local), local));
                }
            }
        } else {
            var resolver = new SkillNameResolver();
            for (var reference : skills) {
                var qualified = resolver.resolve(client, reference);
                targets.add(new UpdateTarget(qualified.qualifiedName(), findInstalled(scanner, baseDir, qualified)));
            }
        }
        return targets;
    }

    private UpdateSummary updateTargets(SkillHubClient client, Path baseDir, List<UpdateTarget> targets) {
        Map<String, String> serverDigests = fetchServerDigests(client);
        var updated = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        var installer = new SkillInstaller();
        for (var target : targets) {
            String outcome = updateOne(client, baseDir, serverDigests, installer, target);
            if (outcome == null) {
                updated.add(target.qualifiedName());
            } else {
                skipped.add(outcome);
            }
        }
        return new UpdateSummary(updated, skipped);
    }

    private String updateOne(SkillHubClient client, Path baseDir, Map<String, String> serverDigests,
                             SkillInstaller installer, UpdateTarget target) {
        var installed = target.installed();
        var marker = installed != null ? installed.marker() : null;
        if (marker == null || !marker.isManaged()) {
            return target.qualifiedName() + (installed == null ? " (not installed, use pull)" : " (not hub-managed, use pull)");
        }
        String serverDigest = serverDigests.get(target.qualifiedName());
        if (serverDigest == null) {
            return target.qualifiedName() + " (not found on server)";
        }
        String state = SkillStatus.of(marker, installed.digest(), serverDigest, true);
        if (!force && !SkillStatus.OUTDATED.equals(state)) {
            return target.qualifiedName() + (SkillStatus.MODIFIED.equals(state)
                    ? " (modified locally, pass --force to overwrite)" : " (already up to date)");
        }
        try {
            var qualified = target.qualifiedName().split("/", 2);
            var archive = client.archive(qualified[0], qualified[1]);
            var dir = baseDir.resolve(qualified[0]).resolve(qualified[1]);
            var source = new SkillHubMarker.Marker(target.qualifiedName(), archive.id(),
                    archive.digest() != null ? archive.digest() : "", serverUrl(), null);
            installer.install(dir, archive.bytes(), source, force);
            return null;
        } catch (IllegalStateException e) {
            return target.qualifiedName() + " (" + e.getMessage() + ")";
        }
    }

    private LocalSkillScanner.LocalSkill findInstalled(LocalSkillScanner scanner, Path baseDir,
                                                       SkillNameResolver.QualifiedName qualified) {
        for (var local : scanner.scan(baseDir)) {
            if (qualified.qualifiedName().equals(displayName(local))) return local;
        }
        return null;
    }

    private String displayName(LocalSkillScanner.LocalSkill local) {
        if (local.marker() != null && local.marker().qualifiedName() != null) return local.marker().qualifiedName();
        String qualified = local.qualifiedName();
        return qualified == null || qualified.isEmpty() ? local.name() : qualified;
    }

    private String markerName(LocalSkillScanner.LocalSkill local) {
        return local.marker() != null && local.marker().qualifiedName() != null
                ? local.marker().qualifiedName()
                : displayName(local);
    }

    private Map<String, String> fetchServerDigests(SkillHubClient client) {
        SkillHubSearchResponse response = client.search(null, null, null, 200);
        var result = new HashMap<String, String>();
        if (response.skills != null) {
            for (var skill : response.skills) {
                if (skill.qualifiedName != null) result.put(skill.qualifiedName, skill.digest);
            }
        }
        return result;
    }

    private void printSummary(UpdateSummary summary) {
        if (json()) {
            var json = new LinkedHashMap<String, Object>();
            json.put("updated", summary.updated());
            json.put("skipped", summary.skipped());
            HubRenderer.printJson(json);
        } else {
            for (var name : summary.updated()) ConsoleWriter.println("Updated " + name);
            for (var reason : summary.skipped()) ConsoleWriter.println("Skipped " + reason);
            if (summary.updated().isEmpty()) ConsoleWriter.println("  (nothing to update)");
        }
    }

    private record UpdateSummary(List<String> updated, List<String> skipped) {
    }

    private record UpdateTarget(String qualifiedName, LocalSkillScanner.LocalSkill installed) {
    }
}
