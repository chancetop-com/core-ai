package ai.core.cli.hub;

import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubServerMatch;
import ai.core.api.server.mcphub.HubServerView;
import ai.core.api.server.mcphub.HubToolDetail;
import ai.core.api.server.mcphub.HubToolSummary;
import ai.core.api.server.mcphub.HubToolsResponse;
import ai.core.api.server.skillhub.SkillHubNamespaceMatch;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.api.server.skillhub.SkillHubSummary;
import ai.core.cli.ConsoleWriter;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-readable rendering for hub commands (two-space indent, stable columns) plus
 * single-line JSON output. JSON mode is the machine contract: field names mirror the
 * server API exactly, and errors also go to stdout as JSON.
 * <p>
 * {@link #searchText(HubToolsResponse)} and {@link #skillSearchText(SkillHubSearchResponse)}
 * share the same two-level group/item renderer ({@link #searchText(String, String, String, String, List, List)}):
 * a brand line over groups with a positive score, then the diversified item rows.
 *
 * @author stephen
 */
public class HubRenderer {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    public static void printJson(Object value) {
        ConsoleWriter.println(JsonUtil.toJson(value));
    }

    public static void printCallJson(HubCallResponse response) {
        printJson(response);
    }

    public static void printErrorJson(int statusCode, String code, String message) {
        ConsoleWriter.println(JsonUtil.toJson(Map.of("error", Map.of(
                "code", code, "message", message == null ? "" : message, "status", statusCode))));
    }

    public String serversText(List<HubServerView> servers) {
        if (servers.isEmpty()) return "  (no mcp servers visible)\n";
        var sb = new StringBuilder(256);
        int nameWidth = servers.stream().mapToInt(s -> s.name.length()).max().orElse(1) + 2;
        for (var server : servers) {
            String stale = Boolean.TRUE.equals(server.stale) ? " (stale)" : "";
            String description = server.description == null || server.description.isBlank()
                    ? "" : "  " + server.description;
            String state = server.state == null ? "" : server.state;
            sb.append("  ").append(pad(server.name, nameWidth)).append(pad(state, 14))
                    .append(toolCountText(server.toolCount)).append(stale).append(description).append('\n');
        }
        return sb.toString();
    }

    public String searchText(HubToolsResponse response) {
        var tools = response.tools == null ? List.<HubToolSummary>of() : response.tools;
        var servers = response.servers == null ? List.<HubServerMatch>of() : response.servers;
        var groups = servers.stream()
                .map(server -> new GroupRow(server.name,
                        server.matchedCount != null ? server.matchedCount : 0,
                        server.score != null ? server.score : 0))
                .toList();
        var items = tools.stream()
                .map(tool -> new ItemRow(tool.qualifiedName, tool.server, tool.description,
                        Boolean.TRUE.equals(tool.stale)))
                .toList();
        return searchText("Servers", "Tools", "--on-server", "(no matching tools)", groups, items);
    }

    /** Shared two-level search layout: brand line (score > 0) then padded group-qualified item rows. */
    String searchText(String groupLabel, String itemLabel, String drillDownOption, String noMatchesText,
                      List<GroupRow> groups, List<ItemRow> items) {
        if (items.isEmpty() && groups.isEmpty()) return "  " + noMatchesText + "\n";
        var sb = new StringBuilder(256);
        var brand = groups.stream().filter(group -> group.score() > 0).toList();
        if (!brand.isEmpty()) {
            sb.append(groupLabel).append(" (").append(brand.size()).append("): ");
            for (int i = 0; i < brand.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(brand.get(i).name()).append('(').append(brand.get(i).matchedCount()).append(')');
            }
            sb.append('\n');
            if (!items.isEmpty()) sb.append(itemLabel).append(":\n");
        }
        if (items.isEmpty()) return sb.toString();
        int nameWidth = items.stream().mapToInt(item -> item.qualifiedName().length()).max().orElse(1) + 2;
        var matchedByGroup = new HashMap<String, Integer>();
        for (var group : groups) {
            matchedByGroup.put(group.name(), group.matchedCount());
        }
        var lastIndexByGroup = new HashMap<String, Integer>();
        for (int i = 0; i < items.size(); i++) {
            lastIndexByGroup.put(items.get(i).group(), i);
        }
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            String stale = item.stale() ? " [stale]" : "";
            String description = item.description() == null ? "" : item.description();
            var line = new StringBuilder(128);
            line.append("  ").append(pad(item.qualifiedName(), Math.min(nameWidth, 48))).append(pad(description, 56)).append(stale);
            int matched = matchedByGroup.getOrDefault(item.group(), 1);
            if (matched > 1 && i == lastIndexByGroup.get(item.group())) {
                int shown = (int) items.stream().filter(candidate -> item.group().equals(candidate.group())).count();
                line.append(" (+").append(matched - shown).append(" more, ").append(drillDownOption).append(' ')
                        .append(item.group()).append(')');
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public String skillSearchText(SkillHubSearchResponse response) {
        var skills = response.skills == null ? List.<SkillHubSummary>of() : response.skills;
        var namespaces = response.namespaces == null ? List.<SkillHubNamespaceMatch>of() : response.namespaces;
        var groups = namespaces.stream()
                .map(namespace -> new GroupRow(namespace.namespace,
                        namespace.matchedCount != null ? namespace.matchedCount : 0,
                        namespace.score != null ? namespace.score : 0))
                .toList();
        var items = skills.stream()
                .map(skill -> new ItemRow(skill.qualifiedName, skill.namespace, skill.description, false))
                .toList();
        return searchText("Namespaces", "Skills", "--namespace", "(no matching skills)", groups, items);
    }

    public String detailText(HubToolDetail detail) {
        var sb = new StringBuilder(256);
        String head = "  " + detail.qualifiedName + "\n    ref_id:      " + nz(detail.refId)
                + "\n    server:      " + nz(detail.server)
                + "    server_state: " + nz(detail.serverState) + '\n';
        sb.append(head);
        if (detail.description != null && !detail.description.isBlank()) {
            String descriptionRow = "    description: " + detail.description + '\n';
            sb.append(descriptionRow);
        }
        if (detail.inputSchema != null && !detail.inputSchema.isBlank()) {
            String schemaBlock = "  input_schema:\n" + prettyJson(detail.inputSchema);
            sb.append(schemaBlock);
        }
        return sb.toString();
    }

    public String prettyJson(String json) {
        try {
            var value = MAPPER.readValue(json, Object.class);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            return json;
        }
    }

    private String toolCountText(Integer toolCount) {
        int count = toolCount == null ? 0 : toolCount;
        return count == 1 ? "1 tool  " : count + " tools";
    }

    private String pad(String value, int width) {
        if (value.length() >= width) return value + "  ";
        return value + " ".repeat(width - value.length());
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }

    public record GroupRow(String name, int matchedCount, int score) {
    }

    public record ItemRow(String qualifiedName, String group, String description, boolean stale) {
    }
}
