package ai.core.cli.appserver;

import ai.core.cli.hub.skill.LocalSkillScanner;
import ai.core.cli.hub.skill.SkillHubClient;
import ai.core.cli.hub.skill.SkillHubMarker;
import ai.core.cli.hub.skill.SkillStatus;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Local skill inventory for the desktop panel: the workspace layer and the user layer
 * ({@code .core-ai/skills} directories, one namespace level supported). Each entry is
 * enriched with its hub provenance and freshness (up-to-date / outdated / modified /
 * local / unverified) so the panel can offer updates. Detecting {@code outdated} needs
 * the server; without a client the envelope reports {@code connected:false} and the
 * states stay local-only.
 *
 * @author stephen
 */
public final class LocalSkills {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSkills.class);

    public static ObjectNode list(Path workspace) {
        return list(workspace, null);
    }

    /** {@code client} is null when not signed in — entries then carry local-only states. */
    public static ObjectNode list(Path workspace, SkillHubClient client) {
        var node = Params.object();
        Map<String, String> serverDigests = client == null ? null : serverDigests(client);
        node.put("connected", serverDigests != null);
        var array = node.putArray("skills");
        scan(workspace.resolve(".core-ai/skills"), "workspace", array, 2, serverDigests);
        scan(Path.of(System.getProperty("user.home"), ".core-ai", "skills"), "user", array, 2, serverDigests);
        return node;
    }

    /** Null when the server is unreachable or answers nothing usable — callers then show local-only states. */
    private static Map<String, String> serverDigests(SkillHubClient client) {
        try {
            var response = client.search(null, null, null, 200);
            var result = new HashMap<String, String>();
            if (response.skills != null) {
                for (var skill : response.skills) {
                    if (skill.qualifiedName != null) {
                        result.put(skill.qualifiedName, skill.digest);
                    }
                }
            }
            return result;
        } catch (RuntimeException e) {
            LOGGER.warn("hub skill list failed for the skills panel: {}", e.getMessage());
            return null;
        }
    }

    private static void scan(Path root, String source, ArrayNode out, int depth, Map<String, String> serverDigests) {
        if (depth <= 0 || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (var entry : stream.sorted().toList()) {
                scanEntry(entry, source, out, depth, serverDigests);
            }
        } catch (IOException e) {
            LOGGER.warn("failed to list skills under {}: {}", root, e.getMessage());
        }
    }

    private static void scanEntry(Path entry, String source, ArrayNode out, int depth, Map<String, String> serverDigests) {
        var fileName = entry.getFileName();
        var name = fileName == null ? "" : fileName.toString();
        if (Files.isRegularFile(entry) && name.endsWith(".md")) {
            add(out, name.substring(0, name.length() - 3), source, entry, serverDigests);
        } else if (Files.isDirectory(entry)) {
            if (Files.exists(entry.resolve("SKILL.md"))) {
                add(out, name, source, entry, serverDigests);
            } else {
                scan(entry, source, out, depth - 1, serverDigests);
            }
        }
    }

    private static void add(ArrayNode out, String name, String source, Path path, Map<String, String> serverDigests) {
        var item = out.addObject();
        item.put("name", name);
        item.put("source", source);
        item.put("path", path.toString());
        // Hub installs are directories carrying a marker; flat .md files are always local.
        var marker = Files.isDirectory(path) ? SkillHubMarker.load(path) : null;
        var state = SkillStatus.LOCAL;
        if (marker != null) {
            var serverDigest = serverDigests != null ? serverDigests.get(marker.qualifiedName()) : null;
            state = SkillStatus.of(marker, localDigest(path), serverDigest, serverDigests != null);
            if (marker.qualifiedName() != null) {
                item.put("qualifiedName", marker.qualifiedName());
            }
        }
        item.put("hub", marker != null);
        item.put("state", state);
    }

    private static String localDigest(Path skillDir) {
        try {
            var content = Files.readString(skillDir.resolve("SKILL.md"), StandardCharsets.UTF_8);
            return new LocalSkillScanner().digestOf(skillDir, content);
        } catch (IOException e) {
            LOGGER.debug("failed to digest skill at {}", skillDir, e);
            return null;
        }
    }

    private LocalSkills() {
    }
}
