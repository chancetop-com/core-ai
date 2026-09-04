package ai.core.cli.hub;

import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubServerMatch;
import ai.core.api.server.mcphub.HubServerView;
import ai.core.api.server.mcphub.HubToolDetail;
import ai.core.api.server.mcphub.HubToolSummary;
import ai.core.api.server.mcphub.HubToolsResponse;
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
        if (tools.isEmpty() && servers.isEmpty()) return "  (no matching tools)\n";
        var sb = new StringBuilder(256);
        var brand = servers.stream().filter(server -> server.score != null && server.score > 0).toList();
        if (!brand.isEmpty()) {
            sb.append("Servers (").append(brand.size()).append("): ");
            for (int i = 0; i < brand.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(brand.get(i).name).append('(').append(brand.get(i).matchedCount).append(')');
            }
            sb.append('\n');
            if (!tools.isEmpty()) sb.append("Tools:\n");
        }
        if (tools.isEmpty()) return sb.toString();
        int nameWidth = tools.stream().mapToInt(tool -> tool.qualifiedName.length()).max().orElse(1) + 2;
        var matchedByServer = new HashMap<String, Integer>();
        for (var server : servers) {
            matchedByServer.put(server.name, server.matchedCount != null ? server.matchedCount : 0);
        }
        var lastIndexByServer = new HashMap<String, Integer>();
        for (int i = 0; i < tools.size(); i++) {
            lastIndexByServer.put(tools.get(i).server, i);
        }
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            String stale = Boolean.TRUE.equals(tool.stale) ? " [stale]" : "";
            String description = tool.description == null ? "" : tool.description;
            var line = new StringBuilder(128);
            line.append("  ").append(pad(tool.qualifiedName, Math.min(nameWidth, 48))).append(pad(description, 56)).append(stale);
            int matched = matchedByServer.getOrDefault(tool.server, 1);
            if (matched > 1 && i == lastIndexByServer.get(tool.server)) {
                int shown = (int) tools.stream().filter(t -> tool.server.equals(t.server)).count();
                line.append(" (+").append(matched - shown).append(" more, --on-server ").append(tool.server).append(')');
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
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
}
