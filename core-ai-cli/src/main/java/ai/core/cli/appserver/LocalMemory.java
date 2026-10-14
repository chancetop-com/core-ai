package ai.core.cli.appserver;

import ai.core.cli.memory.GlobalUserMemory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Local memory files for the desktop panel: the knowledge wiki, episodes and daily-logs under
 * {@code {workspace}/.core-ai}, plus the global user memory at {@code ~/.core-ai/MEMORY.md}
 * (surfaced as {@code global/MEMORY.md}). Listing is newest-first and paged; reads cap at 256KB,
 * must stay inside {@code .core-ai} (or be that single global file) and never leave the workspace
 * otherwise.
 *
 * @author stephen
 */
public final class LocalMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalMemory.class);
    private static final long MAX_READ_BYTES = 256 * 1024;
    private static final List<String> ROOTS = List.of("knowledge", "episodes", "daily-logs");
    private static final String GLOBAL_PREFIX = "global/";
    private static final int DEFAULT_PAGE = 200;
    private static final int MAX_PAGE = 500;

    public static ObjectNode list(Path workspace, ObjectNode params) {
        return list(workspace, params, GlobalUserMemory.defaultPath());
    }

    static ObjectNode list(Path workspace, ObjectNode params, Path globalFile) {
        int offset = Params.intParam(params, "cursor", 0);
        int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_PAGE), MAX_PAGE));
        var root = workspace.resolve(".core-ai");
        var entries = new ArrayList<Entry>();
        for (var relative : ROOTS) {
            collect(root, root.resolve(relative), entries);
        }
        collectGlobal(globalFile, entries);
        entries.sort(Comparator.comparing(Entry::modified).reversed());
        int end = Math.min(offset + limit, entries.size());
        var page = offset < end ? entries.subList(offset, end) : List.<Entry>of();
        var node = Params.object();
        var array = node.putArray("files");
        for (var entry : page) {
            var item = array.addObject();
            item.put("path", entry.path());
            item.put("size", entry.size());
            item.put("modifiedAt", entry.modified().toString());
        }
        if (end < entries.size()) {
            node.put("nextCursor", String.valueOf(end));
        }
        return node;
    }

    public static ObjectNode read(Path workspace, ObjectNode params) {
        return read(workspace, params, GlobalUserMemory.defaultPath());
    }

    static ObjectNode read(Path workspace, ObjectNode params, Path globalFile) {
        var relative = Params.requiredText(params, "path");
        if (relative.startsWith(GLOBAL_PREFIX)) {
            return readGlobal(globalFile, relative);
        }
        var root = CanonicalPath.ofOrNormalized(workspace.resolve(".core-ai"));
        var resolved = CanonicalPath.of(root.resolve(relative).normalize());
        if (resolved == null || !resolved.startsWith(root)) {
            throw RpcException.business("PATH_NOT_ALLOWED", "path must be inside the workspace .core-ai directory");
        }
        if (!Files.isRegularFile(resolved)) {
            throw RpcException.business("FILE_NOT_FOUND", "memory file not found: " + relative, "path", relative);
        }
        return readContent(resolved, root.relativize(resolved).toString().replace('\\', '/'));
    }

    /** The global user memory lives in the user's home, not the workspace — only that one file is reachable. */
    private static ObjectNode readGlobal(Path globalFile, String relative) {
        var fileName = globalFile.getFileName();
        var expected = fileName == null ? "" : GLOBAL_PREFIX + fileName;
        if (!expected.equals(relative)) {
            throw RpcException.business("PATH_NOT_ALLOWED", "only the global memory file can be read under " + GLOBAL_PREFIX);
        }
        if (!Files.isRegularFile(globalFile)) {
            throw RpcException.business("FILE_NOT_FOUND", "memory file not found: " + relative, "path", relative);
        }
        return readContent(globalFile, relative);
    }

    private static ObjectNode readContent(Path file, String path) {
        try {
            if (Files.size(file) > MAX_READ_BYTES) {
                throw RpcException.business("FILE_TOO_LARGE", "memory file is larger than 256KB; open it on disk instead");
            }
            var node = Params.object();
            node.put("path", path);
            node.put("content", Files.readString(file, StandardCharsets.UTF_8));
            return node;
        } catch (IOException e) {
            throw RpcException.business("READ_FAILED", "failed to read memory file: " + e.getMessage(), e);
        }
    }

    private static void collect(Path root, Path dir, List<Entry> entries) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> entries.add(toEntry(root, file)));
        } catch (IOException e) {
            LOGGER.warn("failed to list memory files under {}: {}", dir, e.getMessage());
        }
    }

    private static void collectGlobal(Path globalFile, List<Entry> entries) {
        if (!Files.isRegularFile(globalFile)) {
            return;
        }
        try {
            entries.add(new Entry(GLOBAL_PREFIX + globalFile.getFileName(),
                    Files.size(globalFile), Files.getLastModifiedTime(globalFile).toInstant()));
        } catch (IOException e) {
            LOGGER.warn("failed to list global memory {}: {}", globalFile, e.getMessage());
        }
    }

    private static Entry toEntry(Path root, Path file) {
        try {
            var relative = root.relativize(file).toString().replace('\\', '/');
            return new Entry(relative, Files.size(file), Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            return new Entry("", 0, Instant.EPOCH);
        }
    }

    private LocalMemory() {
    }

    private record Entry(String path, long size, Instant modified) { }
}
