package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import ai.core.cli.http.RemoteApiException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
@Command(name = "list", description = "List locally installed skills with their hub/outdated/modified status")
class SkillListCommand extends HubCommandBase {
    @Option(names = "--workspace", description = "Only list skills installed in {cwd}/.core-ai/skills")
    boolean workspace;

    @Option(names = "--all", description = "List skills from both the user and workspace roots")
    boolean all;

    @Override
    protected Integer execute() {
        var rows = collectRows();
        if (json()) {
            HubRenderer.printJson(rows);
            return HubExitCodes.SUCCESS;
        }
        if (rows.isEmpty()) {
            ConsoleWriter.println("  (no skills installed)");
            return HubExitCodes.SUCCESS;
        }
        int width = rows.stream().mapToInt(row -> ((String) row.get("name")).length()).max().orElse(1) + 2;
        for (var row : rows) {
            String name = (String) row.get("name");
            String state = (String) row.get("state");
            ConsoleWriter.println("  " + pad(name, Math.min(width, 48)) + "  " + state + stateNote(row));
        }
        return HubExitCodes.SUCCESS;
    }

    private List<Map<String, Object>> collectRows() {
        var skills = scanRoots();
        Map<String, ServerSkill> serverDigests = serverDigests();
        boolean serverAvailable = serverDigests != null;
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : skills) {
            var marker = row.local().marker();
            var server = marker == null ? null : serverDigests.get(marker.qualifiedName());
            String state = SkillStatus.of(marker, row.local().digest(),
                    server != null ? server.digest() : null, serverAvailable);
            rows.add(rowJson(row, marker, state, server));
        }
        return rows;
    }

    private List<Row> scanRoots() {
        var scanner = new LocalSkillScanner();
        var skills = new ArrayList<Row>();
        var seen = new HashMap<String, Boolean>();   // workspace rows win over user rows
        for (var root : roots()) {
            for (var local : scanner.scan(root)) {
                String key = local.qualifiedName().isEmpty() ? local.name() : local.qualifiedName();
                if (seen.putIfAbsent(key, Boolean.TRUE) != null) continue;
                skills.add(new Row(local, root));
            }
        }
        return skills;
    }

    private List<Path> roots() {
        var roots = new ArrayList<Path>();
        if (workspace) {
            roots.add(SkillLocations.workspaceSkillsDir());
        } else if (all) {
            roots.add(SkillLocations.workspaceSkillsDir());
            roots.add(SkillLocations.userSkillsDir());
        } else {
            roots.add(SkillLocations.userSkillsDir());
        }
        return roots;
    }

    /** Null when the server is unreachable — the caller then shows local-only statuses. */
    private Map<String, ServerSkill> serverDigests() {
        try {
            SkillHubSearchResponse response = skillClient().search(null, null, null, 200);
            var result = new HashMap<String, ServerSkill>();
            if (response.skills != null) {
                for (var skill : response.skills) {
                    if (skill.qualifiedName != null) {
                        result.put(skill.qualifiedName, new ServerSkill(skill.digest, skill.updatedAt));
                    }
                }
            }
            return result;
        } catch (RemoteApiException | IllegalStateException e) {
            metadata("server unreachable, hub status shown without remote digest comparison: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> rowJson(Row row, SkillHubMarker.Marker marker, String state, ServerSkill server) {
        var json = new LinkedHashMap<String, Object>();
        json.put("name", displayName(row, marker));
        json.put("qualified_name", marker != null ? marker.qualifiedName() : row.local().qualifiedName());
        json.put("dir", row.local().skillDir().toString());
        json.put("source", marker == null ? "local" : "hub");
        json.put("state", state);
        json.put("local_digest", row.local().digest());
        json.put("marker_digest", marker != null ? marker.digest() : null);
        json.put("server_digest", server != null ? server.digest() : null);
        return json;
    }

    private String displayName(Row row, SkillHubMarker.Marker marker) {
        if (marker != null && marker.qualifiedName() != null) return marker.qualifiedName();
        var qualified = row.local().qualifiedName();
        return qualified == null || qualified.isEmpty() ? row.local().name() : qualified;
    }

    private String stateNote(Map<String, Object> row) {
        return switch ((String) row.get("state")) {
            case "outdated" -> "  (server digest changed)";
            case "modified" -> "  (local files differ from pulled digest)";
            case "unverified" -> "  (cannot verify against server)";
            default -> "";
        };
    }

    private String pad(String value, int width) {
        if (value.length() >= width) return value + "  ";
        return value + " ".repeat(width - value.length());
    }

    private record Row(LocalSkillScanner.LocalSkill local, Path root) {
    }

    private record ServerSkill(String digest, ZonedDateTime updatedAt) {
    }
}
