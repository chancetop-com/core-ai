package ai.core.cli.hub.skill;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "show", description = "Show skill metadata and SKILL.md content")
class SkillShowCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "ns/name | name", description = "Qualified skill name, or a bare name if unique")
    String skillRef;

    @Override
    protected Integer execute() {
        var resolved = new SkillNameResolver().resolve(skillClient(), skillRef);
        var detail = skillClient().show(resolved.namespace(), resolved.name());
        if (json()) {
            HubRenderer.printJson(detail);
        } else if (raw()) {
            ConsoleWriter.print(text(detail.content));
        } else {
            ConsoleWriter.print(text(humanText(detail)));
        }
        return HubExitCodes.SUCCESS;
    }

    private String humanText(ai.core.api.server.skillhub.SkillHubDetail detail) {
        var sb = new StringBuilder(512);
        sb.append("  ").append(detail.qualifiedName).append('\n');
        if (detail.description != null && !detail.description.isBlank()) {
            sb.append("    description: ").append(detail.description).append('\n');
        }
        sb.append("    namespace:   ").append(nz(detail.namespace))
                .append("\n    source_type: ").append(nz(detail.sourceType))
                .append("\n    digest:      ").append(nz(detail.digest))
                .append("\n    updated_at:  ").append(detail.updatedAt == null ? "" : detail.updatedAt.toLocalDate())
                .append('\n');
        if (detail.allowedTools != null && !detail.allowedTools.isEmpty()) {
            sb.append("    allowed_tools: ").append(String.join(", ", detail.allowedTools)).append('\n');
        }
        if (detail.metadata != null && !detail.metadata.isEmpty()) {
            var metadata = new StringBuilder();
            for (var entry : detail.metadata.entrySet()) {
                if (!metadata.isEmpty()) metadata.append(", ");
                metadata.append(entry.getKey()).append('=').append(entry.getValue());
            }
            sb.append("    metadata:     ").append(metadata).append('\n');
        }
        if (detail.resources != null && !detail.resources.isEmpty()) {
            sb.append("    resources:\n");
            for (var resource : detail.resources) {
                sb.append("      - ").append(resource.path);
                if (resource.size != null) sb.append(" (").append(resource.size).append(" bytes)");
                sb.append('\n');
            }
            sb.append("    (resource content: core-ai-cli skill pull ").append(detail.qualifiedName).append(")\n");
        }
        return sb.append('\n').append(detail.content == null ? "" : detail.content).toString();
    }

    /** Ensures content ends with a newline so the shell/agent gets a clean last line. */
    private String text(String content) {
        if (content == null || content.isEmpty()) return "";
        return content.endsWith("\n") ? content : content + "\n";
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }
}
