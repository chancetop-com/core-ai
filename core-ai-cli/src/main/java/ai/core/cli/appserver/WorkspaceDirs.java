package ai.core.cli.appserver;

import ai.core.cli.utils.PathUtils;
import ai.core.utils.AtomicFiles;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the session directory for a workspace. Ownership is keyed by a stable project id stored in
 * {@code <workspace>/.core-ai/workspace.id}; the sessions base holds the id-to-directory registry.
 * A moved project keeps its id and therefore its history; a copied project receives a new id.
 *
 * @author stephen
 */
public final class WorkspaceDirs {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceDirs.class);
    private static final String WORKSPACE_ID = "workspace.id";
    private static final String REGISTRY_FILE = "workspaces.json";
    private static final String REGISTRY_LOCK = "workspaces.lock";
    private static final String OWNER_FILE = "owner.json";
    private static final int MAX_COLLISIONS = 1000;

    private static final Map<CacheKey, Path> CACHE = new ConcurrentHashMap<>();

    public static Path ensure(Path workspace) {
        return ensure(workspace, PathUtils.SESSIONS_BASE_DIR);
    }

    static Path ensure(Path workspace, Path baseDir) {
        var canonical = CanonicalPath.ofOrNormalized(workspace);
        return CACHE.computeIfAbsent(new CacheKey(canonical, baseDir), key -> ensureUncached(key.canonical(), key.baseDir()));
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static Path ensureUncached(Path canonical, Path baseDir) {
        ensureBaseDir(baseDir);
        try (var channel = FileChannel.open(baseDir.resolve(REGISTRY_LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!lock.isValid()) throw new IllegalStateException("failed to acquire the workspace registry lock");
            return resolveUnderLock(canonical, baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to resolve session directory: " + canonical, e);
        }
    }

    private static void ensureBaseDir(Path baseDir) {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create sessions directory: " + baseDir, e);
        }
    }

    private static Path resolveUnderLock(Path canonical, Path baseDir) {
        var identity = identity(canonical);
        var registry = loadRegistry(baseDir);
        var entry = registry.workspaces.get(identity);
        if (entry != null && (entry.dir == null || entry.dir.isBlank())) {
            entry = null;
        }
        if (entry != null && isCopy(entry, canonical)) {
            identity = newIdentity(canonical);
            entry = null;
        }
        if (entry != null) {
            return existingSessionsDir(baseDir, registry, entry, identity, canonical);
        }
        var dirName = chooseDir(baseDir, registry, basename(canonical), identity);
        var sessionsDir = baseDir.resolve(dirName);
        ensureDirectory(sessionsDir);
        writeOwner(sessionsDir, identity, canonical);
        registry.workspaces.put(identity, entry(dirName, canonical));
        saveRegistryQuietly(baseDir, registry, "failed to save session workspace registry");
        return sessionsDir;
    }

    private static Path existingSessionsDir(Path baseDir, Registry registry, Registry.Entry entry,
                                            String identity, Path canonical) {
        entry.lastPath = canonical.toString();
        saveRegistryQuietly(baseDir, registry, "failed to update session workspace registry");
        var sessionsDir = baseDir.resolve(entry.dir);
        ensureDirectory(sessionsDir);
        if (!Files.exists(sessionsDir.resolve(OWNER_FILE))) {
            writeOwner(sessionsDir, identity, canonical);
        }
        return sessionsDir;
    }

    private static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create session directory: " + dir, e);
        }
    }

    private static void saveRegistryQuietly(Path baseDir, Registry registry, String message) {
        try {
            saveRegistry(baseDir, registry);
        } catch (RuntimeException e) {
            LOGGER.warn("{}: {}", message, e.getMessage());
        }
    }

    private static boolean isCopy(Registry.Entry entry, Path canonical) {
        if (entry.lastPath == null || entry.lastPath.isBlank() || entry.lastPath.equals(canonical.toString())) return false;
        try {
            return Files.exists(Path.of(entry.lastPath));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String identity(Path canonical) {
        var dir = canonical.resolve(".core-ai");
        var file = dir.resolve(WORKSPACE_ID);
        try {
            if (Files.exists(file)) {
                var id = Files.readString(file).strip();
                if (!id.isEmpty()) return id;
            }
            Files.createDirectories(dir);
            var id = UUID.randomUUID().toString();
            AtomicFiles.writeString(file, id);
            appendGitignore(dir);
            return id;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("workspace is not writable, falling back to path identity: {}, error={}", canonical, e.getMessage());
            return "path:" + canonical;
        }
    }

    private static String newIdentity(Path canonical) {
        var dir = canonical.resolve(".core-ai");
        try {
            Files.createDirectories(dir);
            var id = UUID.randomUUID().toString();
            AtomicFiles.writeString(dir.resolve(WORKSPACE_ID), id);
            appendGitignore(dir);
            return id;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to create a new workspace id, falling back to path identity: {}", canonical, e);
            return "path:" + canonical;
        }
    }

    private static void appendGitignore(Path coreAiDir) {
        var gitignore = coreAiDir.resolve(".gitignore");
        try {
            if (Files.exists(gitignore) && Files.readString(gitignore).contains(WORKSPACE_ID)) return;
            Files.writeString(gitignore, "\n" + WORKSPACE_ID + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("failed to update .core-ai/.gitignore: {}", e.getMessage());
        }
    }

    private static String chooseDir(Path baseDir, Registry registry, String basename, String identity) {
        for (int collision = 0; collision < MAX_COLLISIONS; collision++) {
            var candidate = PathUtils.sessionDirName(basename, identity, collision);
            if (claimedByAnother(registry, candidate, identity)) continue;
            var dir = baseDir.resolve(candidate);
            if (!Files.exists(dir)) return candidate;
            var owner = readOwner(dir);
            if (owner == null || identity.equals(owner.id)) return candidate;
        }
        throw new IllegalStateException("too many session directory collisions for " + basename);
    }

    private static boolean claimedByAnother(Registry registry, String candidate, String identity) {
        for (var entry : registry.workspaces.entrySet()) {
            if (!entry.getKey().equals(identity) && candidate.equals(entry.getValue().dir)) return true;
        }
        return false;
    }

    private static String basename(Path canonical) {
        var name = canonical.getFileName();
        return name == null || name.toString().isEmpty() ? "root" : name.toString();
    }

    private static Registry loadRegistry(Path baseDir) {
        var file = baseDir.resolve(REGISTRY_FILE);
        if (!Files.exists(file)) return new Registry();
        try {
            var registry = JsonUtil.fromJson(Registry.class, Files.readString(file));
            if (registry.workspaces == null) registry.workspaces = new LinkedHashMap<>();
            return registry;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("session workspace registry is unreadable, rebuilding from owner files: {}", e.getMessage());
            return rebuildRegistry(baseDir);
        }
    }

    private static Registry rebuildRegistry(Path baseDir) {
        var registry = new Registry();
        try (var dirs = Files.list(baseDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                var name = dir.getFileName();
                if (name == null) return;
                var owner = readOwner(dir);
                if (owner == null || owner.id == null || owner.id.isBlank()) return;
                registry.workspaces.put(owner.id, entry(name.toString(), pathOrNull(owner.lastPath)));
            });
        } catch (IOException e) {
            LOGGER.warn("failed to rebuild session workspace registry: {}", e.getMessage());
        }
        return registry;
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Owner readOwner(Path dir) {
        var file = dir.resolve(OWNER_FILE);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtil.fromJson(Owner.class, Files.readString(file));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeOwner(Path dir, String identity, Path canonical) {
        var owner = new Owner();
        owner.id = identity;
        owner.lastPath = canonical.toString();
        AtomicFiles.writeString(dir.resolve(OWNER_FILE), JsonUtil.toJson(owner));
    }

    private static void saveRegistry(Path baseDir, Registry registry) {
        registry.version = 1;
        AtomicFiles.writeString(baseDir.resolve(REGISTRY_FILE), JsonUtil.toJson(registry));
    }

    private static Registry.Entry entry(String dir, Path lastPath) {
        var entry = new Registry.Entry();
        entry.dir = dir;
        entry.lastPath = lastPath == null ? null : lastPath.toString();
        entry.claimedAt = Instant.now().toString();
        return entry;
    }

    private WorkspaceDirs() {
    }

    private record CacheKey(Path canonical, Path baseDir) {
    }

    public static class Registry {
        public Integer version;
        public Map<String, Entry> workspaces = new LinkedHashMap<>();

        public static class Entry {
            public String dir;
            public String lastPath;
            public String claimedAt;
        }
    }

    public static class Owner {
        public String id;
        public String lastPath;
    }
}
