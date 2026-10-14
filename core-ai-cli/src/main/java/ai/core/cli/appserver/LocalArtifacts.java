package ai.core.cli.appserver;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local artifacts for the desktop panel: generated media ({@code .core-ai/media}), files the agent
 * registered through {@code submit_artifacts} ({@code .core-ai/artifacts/manifest.jsonl}) and subagent
 * outputs ({@code .core-ai/tasks}), newest first, page by page.
 *
 * @author stephen
 */
public final class LocalArtifacts {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalArtifacts.class);
    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 500;
    private static final Pattern IMAGE_NAME = Pattern.compile("\\.(png|jpe?g|gif|webp|svg|bmp|ico)$");
    private static final Pattern VIDEO_NAME = Pattern.compile("\\.(mp4|webm|ogv|mov|m4v)$");

    public static ObjectNode list(Path workspace, ObjectNode params) {
        int offset = Params.intParam(params, "cursor", 0);
        int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_PAGE), MAX_PAGE));
        var entries = collect(workspace);
        int end = Math.min(offset + limit, entries.size());
        var page = offset < end ? entries.subList(offset, end) : List.<Entry>of();
        var node = Params.object();
        var array = node.putArray("files");
        for (var entry : page) {
            var item = array.addObject();
            item.put("name", entry.name());
            item.put("path", entry.path());
            item.put("kind", entry.kind());
            item.put("size", entry.size());
            item.put("createdAt", entry.modified().toString());
        }
        if (end < entries.size()) {
            node.put("nextCursor", String.valueOf(end));
        }
        return node;
    }

    private static List<Entry> collect(Path workspace) {
        var entries = new ArrayList<Entry>();
        collectRoot(workspace, ".core-ai/media/images", "image", entries);
        collectRoot(workspace, ".core-ai/media/videos", "video", entries);
        collectRoot(workspace, ".core-ai/tasks", "file", entries);
        collectRegistered(workspace, entries);
        entries.sort(Comparator.comparing(Entry::modified).reversed());
        return entries;
    }

    /** Files registered through submit_artifacts; entries already covered by the scanned roots stay single. */
    private static void collectRegistered(Path workspace, List<Entry> entries) {
        var registered = LocalArtifactManifest.paths(workspace);
        if (registered.isEmpty()) {
            return;
        }
        var root = workspace.normalize();
        var seen = new HashSet<String>();
        for (var entry : entries) {
            seen.add(entry.path());
        }
        for (var relativePath : registered) {
            if (!seen.add(relativePath)) {
                continue;
            }
            var file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            var fileName = file.getFileName();
            var name = fileName == null ? "" : fileName.toString();
            try {
                entries.add(new Entry(name, relativePath, kindOf(name), Files.size(file), Files.getLastModifiedTime(file).toInstant()));
            } catch (IOException e) {
                LOGGER.warn("failed to read registered artifact {}: {}", file, e.getMessage());
            }
        }
    }

    static String kindOf(String fileName) {
        var lower = fileName.toLowerCase(Locale.ROOT);
        if (IMAGE_NAME.matcher(lower).find()) {
            return "image";
        }
        if (VIDEO_NAME.matcher(lower).find()) {
            return "video";
        }
        return "file";
    }

    private static void collectRoot(Path workspace, String relative, String kind, List<Entry> entries) {
        var root = workspace.resolve(relative);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> entries.add(toEntry(workspace, kind, file)));
        } catch (IOException e) {
            LOGGER.warn("failed to list artifacts under {}: {}", root, e.getMessage());
        }
    }

    private static Entry toEntry(Path workspace, String kind, Path file) {
        try {
            var fileName = file.getFileName();
            var name = fileName == null ? "" : fileName.toString();
            var relative = workspace.relativize(file).toString().replace('\\', '/');
            return new Entry(name, relative, kind, Files.size(file), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            return new Entry("", "", kind, 0, Instant.EPOCH);
        }
    }

    private LocalArtifacts() {
    }

    private record Entry(String name, String path, String kind, long size, Instant modified) { }
}
