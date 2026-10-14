package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillHubSearchResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared update logic behind {@code core-ai-cli skill update} and the desktop's
 * {@code hub/skills/update} RPC: only {@code outdated} skills are replaced, a local copy
 * that drifted from the pulled digest is skipped unless {@code force}.
 *
 * @author stephen
 */
public final class SkillUpdater {
    /** Every hub-marked skill under the root; empty when nothing is installed from the hub. */
    public List<Outcome> updateAll(SkillHubClient client, Path baseDir, boolean force) {
        var scanner = new LocalSkillScanner();
        var names = new ArrayList<String>();
        for (var local : scanner.scan(baseDir)) {
            if (local.marker() != null && local.marker().isManaged()) {
                names.add(displayName(local));
            }
        }
        if (names.isEmpty()) {
            return List.of();
        }
        return updateTargets(client, baseDir, names, force);
    }

    public List<Outcome> update(SkillHubClient client, Path baseDir, String qualifiedName, boolean force) {
        return updateTargets(client, baseDir, List.of(qualifiedName), force);
    }

    private List<Outcome> updateTargets(SkillHubClient client, Path baseDir, List<String> qualifiedNames, boolean force) {
        Map<String, String> serverDigests = fetchServerDigests(client);
        var installer = new SkillInstaller();
        var outcomes = new ArrayList<Outcome>();
        for (var qualifiedName : qualifiedNames) {
            outcomes.add(updateOne(client, baseDir, serverDigests, installer, qualifiedName, force));
        }
        return outcomes;
    }

    private Outcome updateOne(SkillHubClient client, Path baseDir, Map<String, String> serverDigests,
                              SkillInstaller installer, String qualifiedName, boolean force) {
        var installed = findInstalled(baseDir, qualifiedName);
        var marker = installed != null ? installed.marker() : null;
        if (marker == null || !marker.isManaged()) {
            return new Outcome(qualifiedName, false,
                    installed == null ? "(not installed, use pull)" : "(not hub-managed, use pull)");
        }
        String serverDigest = serverDigests.get(qualifiedName);
        if (serverDigest == null) {
            return new Outcome(qualifiedName, false, "(not found on server)");
        }
        String state = SkillStatus.of(marker, installed.digest(), serverDigest, true);
        if (!force && !SkillStatus.OUTDATED.equals(state)) {
            return new Outcome(qualifiedName, false, SkillStatus.MODIFIED.equals(state)
                    ? "(modified locally, pass --force to overwrite)" : "(already up to date)");
        }
        try {
            var parts = qualifiedName.split("/", 2);
            var archive = client.archive(parts[0], parts[1]);
            var dir = baseDir.resolve(parts[0]).resolve(parts[1]);
            var source = new SkillHubMarker.Marker(qualifiedName, archive.id(),
                    archive.digest() != null ? archive.digest() : "", client.serverUrl(), null);
            installer.install(dir, archive.bytes(), source, force);
            return new Outcome(qualifiedName, true, null);
        } catch (IllegalStateException e) {
            return new Outcome(qualifiedName, false, "(" + e.getMessage() + ")");
        }
    }

    private LocalSkillScanner.LocalSkill findInstalled(Path baseDir, String qualifiedName) {
        // The canonical pull location first: a locally edited SKILL.md may no longer parse as a
        // skill, but the directory and its marker still identify the install.
        var parts = qualifiedName.split("/", 2);
        if (parts.length == 2) {
            var dir = baseDir.resolve(parts[0]).resolve(parts[1]);
            var marker = Files.isDirectory(dir) ? SkillHubMarker.load(dir) : null;
            if (marker != null) {
                return new LocalSkillScanner.LocalSkill(parts[1], qualifiedName, dir, null, List.of(), digestOf(dir), marker);
            }
        }
        var scanner = new LocalSkillScanner();
        for (var local : scanner.scan(baseDir)) {
            if (qualifiedName.equals(displayName(local))) {
                return local;
            }
        }
        return null;
    }

    private String digestOf(Path skillDir) {
        try {
            var skillFile = skillDir.resolve("SKILL.md");
            if (!Files.exists(skillFile)) {
                return null;
            }
            return new LocalSkillScanner().digestOf(skillDir, Files.readString(skillFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private String displayName(LocalSkillScanner.LocalSkill local) {
        if (local.marker() != null && local.marker().qualifiedName() != null) {
            return local.marker().qualifiedName();
        }
        String qualified = local.qualifiedName();
        return qualified == null || qualified.isEmpty() ? local.name() : qualified;
    }

    private Map<String, String> fetchServerDigests(SkillHubClient client) {
        SkillHubSearchResponse response = client.search(null, null, null, 200);
        var result = new HashMap<String, String>();
        if (response.skills != null) {
            for (var skill : response.skills) {
                if (skill.qualifiedName != null) {
                    result.put(skill.qualifiedName, skill.digest);
                }
            }
        }
        return result;
    }

    /** {@code reason} is null when the skill was replaced, otherwise the "(…)" text both front ends print. */
    public record Outcome(String qualifiedName, boolean updated, String reason) {
    }
}
