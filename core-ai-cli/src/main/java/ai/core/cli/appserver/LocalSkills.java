package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Local skill inventory for the desktop panel: the workspace layer and the user layer
 * ({@code .core-ai/skills} directories, one namespace level supported).
 *
 * @author stephen
 */
public final class LocalSkills {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSkills.class);

    public static ObjectNode list(Path workspace) {
        var node = Params.object();
        var array = node.putArray("skills");
        scan(workspace.resolve(".core-ai/skills"), "workspace", array, 2);
        scan(Path.of(System.getProperty("user.home"), ".core-ai", "skills"), "user", array, 2);
        return node;
    }

    private static void scan(Path root, String source, ArrayNode out, int depth) {
        if (depth <= 0 || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (var entry : stream.sorted().toList()) {
                scanEntry(entry, source, out, depth);
            }
        } catch (IOException e) {
            LOGGER.warn("failed to list skills under {}: {}", root, e.getMessage());
        }
    }

    private static void scanEntry(Path entry, String source, ArrayNode out, int depth) {
        var fileName = entry.getFileName();
        var name = fileName == null ? "" : fileName.toString();
        if (Files.isRegularFile(entry) && name.endsWith(".md")) {
            add(out, name.substring(0, name.length() - 3), source, entry);
        } else if (Files.isDirectory(entry)) {
            if (Files.exists(entry.resolve("SKILL.md"))) {
                add(out, name, source, entry);
            } else {
                scan(entry, source, out, depth - 1);
            }
        }
    }

    private static void add(ArrayNode out, String name, String source, Path path) {
        var item = out.addObject();
        item.put("name", name);
        item.put("source", source);
        item.put("path", path.toString());
    }

    private LocalSkills() {
    }
}
