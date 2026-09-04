package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubCliError;
import ai.core.utils.JsonUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uploads a local skill directory to the server ({@code POST /api/skills/upload},
 * requires {@code skill.manage} on the server). The namespace is derived from the
 * authenticated server account — skills are always pushed into your own namespace.
 *
 * @author stephen
 */
@Command(name = "push", description = "Upload a local skill to core-ai-server")
class SkillPushCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "dir", description = "Skill directory containing SKILL.md")
    Path dir;

    @Override
    protected Integer execute() {
        var absolute = dir.toAbsolutePath();
        if (!Files.isRegularFile(absolute.resolve("SKILL.md"))) {
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "no SKILL.md found in " + absolute);
        }
        var scanner = new LocalSkillScanner();
        var local = scanner.scan(absolute).stream().findFirst()
                .orElseThrow(() -> new HubCliError(HubExitCodes.TOOL_ERROR,
                        "SKILL.md is missing a name/description frontmatter: " + absolute));
        var files = new LinkedHashMap<String, Path>();
        files.put("skill_file", absolute.resolve("SKILL.md"));
        for (var resource : local.resources()) {
            files.put(resource, absolute.resolve(resource));
        }
        metadata("uploading " + local.name() + " ...");
        String body = skillClient().push(files);
        if (body == null) {
            throw new HubCliError(HubExitCodes.TOOL_ERROR, "upload failed");
        }
        if (json()) {
            ConsoleWriter.println(body);
        } else {
            String qualifiedName = qualifiedNameFrom(body);
            ConsoleWriter.println("Uploaded " + (qualifiedName != null ? qualifiedName : local.name())
                    + " to the server (" + files.size() + " file(s))");
        }
        return HubExitCodes.SUCCESS;
    }

    @SuppressWarnings("unchecked")
    private String qualifiedNameFrom(String body) {
        try {
            Map<String, Object> response = JsonUtil.fromJson(Map.class, body);
            var value = response.get("qualified_name");
            return value != null ? String.valueOf(value) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
