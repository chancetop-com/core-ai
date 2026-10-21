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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local artifacts for the desktop panel: generated media ({@code .core-ai/media}), files the agent
 * registered through {@code submit_artifacts} ({@code .core-ai/artifacts/manifest.jsonl}) and subagent
 * outputs ({@code .core-ai/tasks}), newest first, page by page.
 *
 * <p>Without a {@code sessionId} the list is workspace-wide (the desktop Artifacts page). With one, only
 * entries the manifest attributes to that session are returned: media and registered files carry their
 * session, while scanned workspace files (subagent outputs, files from before session attribution) are not
 * attributable and therefore stay out of the per-session view.
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
        var sessionId = Params.optionalText(params, "sessionId");
        var entries = collect(workspace, sessionId);
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
            if (entry.sessionId() != null) {
                item.put("sessionId", entry.sessionId());
            }
        }
        if (end < entries.size()) {
            node.put("nextCursor", String.valueOf(end));
        }
        return node;
    }

    private static List<Entry> collect(Path workspace, String sessionId) {
        var registered = LocalArtifactManifest.entries(workspace);
        var owners = owners(registered);
        var entries = new ArrayList<Entry>();
        collectRoot(workspace, ".core-ai/media/images", "image", owners, entries);
        collectRoot(workspace, ".core-ai/media/videos", "video", owners, entries);
        if (sessionId == null) {
            collectRoot(workspace, ".core-ai/tasks", "file", owners, entries);
        }
        collectRegistered(workspace, registered, entries);
        if (sessionId != null) {
            entries.removeIf(entry -> !sessionId.equals(entry.sessionId()));
        }
        entries.sort(Comparator.comparing(Entry::modified).reversed());
        return entries;
    }

    /** Path to owning session for every manifest line that carries one. */
    private static Map<String, String> owners(List<LocalArtifactManifest.Entry> registered) {
        var owners = new HashMap<String, String>();
        for (var entry : registered) {
            if (entry.sessionId() != null) {
                owners.put(entry.path(), entry.sessionId());
            }
        }
        return owners;
    }

    /** Files registered through the manifest; entries already covered by the scanned roots stay single. */
    private static void collectRegistered(Path workspace, List<LocalArtifactManifest.Entry> registered, List<Entry> entries) {
        if (registered.isEmpty()) {
            return;
        }
        var root = workspace.normalize();
        var seen = new HashSet<String>();
        for (var entry : entries) {
            seen.add(entry.path());
        }
        for (var artifact : registered) {
            if (!seen.add(artifact.path())) {
                continue;
            }
            var file = root.resolve(artifact.path()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            var fileName = file.getFileName();
            var name = fileName == null ? "" : fileName.toString();
            try {
                entries.add(new Entry(name, artifact.path(), kindOf(name), Files.size(file),
                        Files.getLastModifiedTime(file).toInstant(), artifact.sessionId()));
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

    private static void collectRoot(Path workspace, String relative, String kind, Map<String, String> owners,
                                    List<Entry> entries) {
        var root = workspace.resolve(relative);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> entries.add(toEntry(workspace, kind, file, owners)));
        } catch (IOException e) {
            LOGGER.warn("failed to list artifacts under {}: {}", root, e.getMessage());
        }
    }

    private static Entry toEntry(Path workspace, String kind, Path file, Map<String, String> owners) {
        try {
            var fileName = file.getFileName();
            var name = fileName == null ? "" : fileName.toString();
            var relative = workspace.relativize(file).toString().replace('\\', '/');
            return new Entry(name, relative, kind, Files.size(file), Files.getLastModifiedTime(file).toInstant(),
                    owners.get(relative));
        } catch (IOException e) {
            return new Entry("", "", kind, 0, Instant.EPOCH, null);
        }
    }

    private LocalArtifacts() {
    }

    private record Entry(String name, String path, String kind, long size, Instant modified, String sessionId) {
    }
}
