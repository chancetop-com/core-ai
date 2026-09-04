package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
@Command(name = "pull", description = "Download a skill as a ZIP and install it locally")
class SkillPullCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "ns/name | name", description = "Qualified skill name, or a bare name if unique")
    String skillRef;

    @Option(names = "--workspace", description = "Install into {cwd}/.core-ai/skills (overrides the user-level copy)")
    boolean workspace;

    @Option(names = "--to", description = "Install into DIR/{name} instead (flat layout, e.g. .claude/skills)")
    Path toDir;

    @Option(names = "--force", description = "Overwrite an existing directory (unmanaged, or locally modified)")
    boolean force;

    @Override
    protected Integer execute() {
        var client = skillClient();
        var qualified = new SkillNameResolver().resolve(client, skillRef);
        var archive = client.archive(qualified.namespace(), qualified.name());
        var digest = archive.digest() != null ? archive.digest() : "";
        Path targetDir = toDir != null ? toDir.resolve(qualified.name())
                : baseDir().resolve(qualified.namespace()).resolve(qualified.name());
        var source = new SkillHubMarker.Marker(qualified.qualifiedName(), archive.id(), digest, serverUrl(), null);
        var outcome = new SkillInstaller().install(targetDir, archive.bytes(), source, force);
        if (json()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("qualified_name", qualified.qualifiedName());
            json.put("id", archive.id());
            json.put("digest", digest);
            json.put("installed_to", outcome.dir().toAbsolutePath().toString());
            json.put("files", outcome.files());
            json.put("status", outcome.replaced() ? "installed" : "up_to_date");
            HubRenderer.printJson(json);
        } else if (outcome.replaced()) {
            ConsoleWriter.println("Installed " + qualified.qualifiedName() + " to " + outcome.dir()
                    + " (" + outcome.files() + " files)");
        } else {
            ConsoleWriter.println("Skill " + qualified.qualifiedName() + " is already up to date at " + outcome.dir());
        }
        return HubExitCodes.SUCCESS;
    }

    private Path baseDir() {
        return workspace ? SkillLocations.workspaceSkillsDir() : SkillLocations.userSkillsDir();
    }
}
