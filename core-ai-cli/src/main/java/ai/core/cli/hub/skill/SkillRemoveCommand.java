package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;

/**
 * Removes a locally installed skill (hub-managed or plain local) from the user or
 * workspace root.
 *
 * @author stephen
 */
@Command(name = "remove", description = "Remove a locally installed skill")
class SkillRemoveCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "ns/name | name", description = "Skill to remove (hub-managed name or local name)")
    String skillRef;

    @Option(names = "--workspace", description = "Remove from {cwd}/.core-ai/skills instead of the user-level skills")
    boolean workspace;

    @Override
    protected Integer execute() {
        var root = workspace ? SkillLocations.workspaceSkillsDir() : SkillLocations.userSkillsDir();
        LocalSkillScanner.LocalSkill found = findSkill(root, skillRef);
        if (found == null) {
            throw new HubCliError(HubExitCodes.NOT_FOUND, "skill not installed locally: " + skillRef);
        }
        deleteRecursively(found.skillDir());
        if (json()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("qualified_name", displayName(found));
            data.put("removed_from", found.skillDir().toAbsolutePath().toString());
            HubRenderer.printJson(data);
        } else {
            ConsoleWriter.println("Removed " + displayName(found) + " from " + found.skillDir());
        }
        return HubExitCodes.SUCCESS;
    }

    private LocalSkillScanner.LocalSkill findSkill(Path root, String reference) {
        var scanner = new LocalSkillScanner();
        for (var local : scanner.scan(root)) {
            if (matches(local, reference)) return local;
        }
        return null;
    }

    private boolean matches(LocalSkillScanner.LocalSkill local, String reference) {
        String name = displayName(local);
        if (reference.contains("/")) return reference.equals(name);
        String qualified = local.qualifiedName();
        return reference.equals(name) || qualified != null && qualified.equals(reference) || local.name().equals(reference);
    }

    private String displayName(LocalSkillScanner.LocalSkill local) {
        if (local.marker() != null && local.marker().qualifiedName() != null) return local.marker().qualifiedName();
        String qualified = local.qualifiedName();
        return qualified == null || qualified.isEmpty() ? local.name() : qualified;
    }

    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            var paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (var path : paths) Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to remove " + dir, e);
        }
    }
}
