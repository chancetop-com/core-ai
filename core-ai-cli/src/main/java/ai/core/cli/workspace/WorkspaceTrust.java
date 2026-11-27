package ai.core.cli.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strictly read-only workspace trust evaluation for the app-server engine.
 *
 * A workspace can contribute executable configuration: hooks, MCP servers, and skills that steer
 * the agent. The engine evaluates a fingerprint of those inputs on every boot. Desktop
 * {@code WorkspaceTrustStore} is the sole writer of {@code workspace-trust.json} (schemaVersion 2,
 * including revoked tombstones); this class never modifies the file.
 *
 * The desktop-managed MCP mode uses its own fingerprint (CLI MCP files are not loaded there) and
 * its own trust record key ({@code <canonical>#desktop-mcp}). When no desktop record exists yet,
 * a matching legacy CLI record is honored for the current boot and reported via
 * {@link Evaluation#migration()} so the desktop host can persist the desktop fingerprint.
 */
public final class WorkspaceTrust {
    public static final String TRUST_SCHEMA_VERSION = "2";
    public static final String TRUST_FILE_NAME = "workspace-trust.json";
    public static final String RESTRICTED_PROPERTY = "core.ai.workspace.restricted";
    public static final String PLUGIN_HOME_ENV = "CORE_AI_PLUGIN_HOME";
    public static final String DESKTOP_MCP_KEY_SUFFIX = "#desktop-mcp";

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceTrust.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1", "2");
    private static final List<String> CONFIG_FILES = List.of(
            ".core-ai/agent.properties",
            ".core-ai/hooks.json",
            ".core-ai/MCP.json",
            ".core-ai/mcp.json",
            "MCP.json",
            "mcp.json"
    );
    private static final Set<String> DESKTOP_SKIPPED_FILES = Set.of(
            ".core-ai/MCP.json",
            ".core-ai/mcp.json",
            "MCP.json",
            "mcp.json"
    );
    private static final List<String> CONFIG_DIRS = List.of(
            ".core-ai/plugins",
            ".core-ai/skills"
    );

    public static Evaluation evaluate(Path workspace) {
        return evaluate(workspace, Mode.CLI);
    }

    public static Evaluation evaluate(Path workspace, Mode mode) {
        return evaluate(workspace, trustFile(), mode);
    }

    static Evaluation evaluate(Path workspace, Path trustFile, Mode mode) {
        var workspaceKey = canonicalKey(workspace);
        var key = recordKey(workspaceKey, mode);
        var snapshot = fingerprint(workspace, mode);
        var entry = readEntry(trustFile, key);
        if (entry != null && entry.revoked()) {
            return new Evaluation(State.RESTRICTED, key, workspaceKey, snapshot.fingerprint(),
                    snapshot.hasExecutableInputs(), "trust_revoked", false, null);
        }
        if (entry != null) {
            if (snapshot.fingerprint().equals(entry.fingerprint())) {
                return new Evaluation(State.TRUSTED, key, workspaceKey, snapshot.fingerprint(),
                        snapshot.hasExecutableInputs(), null, false, null);
            }
            return new Evaluation(State.RESTRICTED, key, workspaceKey, snapshot.fingerprint(),
                    snapshot.hasExecutableInputs(), "fingerprint_changed", false, null);
        }
        if (!snapshot.hasExecutableInputs()) {
            return new Evaluation(State.TRUSTED, key, workspaceKey, snapshot.fingerprint(),
                    false, "no_workspace_config", false, null);
        }
        if (mode == Mode.DESKTOP_MCP) {
            var migration = detectLegacyMigration(trustFile, workspace, workspaceKey, key, snapshot.fingerprint());
            if (migration != null) {
                return new Evaluation(State.TRUSTED, key, workspaceKey, snapshot.fingerprint(),
                        true, "migrated_from_cli_record", true, migration);
            }
        }
        return new Evaluation(State.RESTRICTED, key, workspaceKey, snapshot.fingerprint(),
                true, "not_trusted", false, null);
    }

    private static Migration detectLegacyMigration(Path trustFile, Path workspace, String workspaceKey,
                                                   String desktopKey, String desktopFingerprint) {
        var legacyEntry = readEntry(trustFile, workspaceKey);
        if (legacyEntry == null || legacyEntry.revoked() || legacyEntry.fingerprint() == null) return null;
        var legacySnapshot = fingerprint(workspace, Mode.CLI);
        if (!legacySnapshot.fingerprint().equals(legacyEntry.fingerprint())) return null;
        return new Migration(workspaceKey, legacySnapshot.fingerprint(), desktopKey, desktopFingerprint);
    }

    public static String recordKey(String workspaceKey, Mode mode) {
        return mode == Mode.DESKTOP_MCP ? workspaceKey + DESKTOP_MCP_KEY_SUFFIX : workspaceKey;
    }

    public static Path trustFile() {
        var home = System.getenv(PLUGIN_HOME_ENV);
        var root = home != null && !home.isBlank()
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".core-ai", "plugin-store");
        return root.resolve(TRUST_FILE_NAME);
    }

    public static String canonicalKey(Path workspace) {
        Path canonical;
        try {
            canonical = workspace.toRealPath();
        } catch (IOException e) {
            canonical = workspace.toAbsolutePath().normalize();
        }
        return canonical.toString();
    }

    static Snapshot fingerprint(Path workspace, Mode mode) {
        var entries = new ArrayList<String>();
        boolean inputs = false;
        for (var relative : CONFIG_FILES) {
            if (mode == Mode.DESKTOP_MCP && DESKTOP_SKIPPED_FILES.contains(relative)) continue;
            var file = workspace.resolve(relative);
            if (!Files.isRegularFile(file)) continue;
            inputs = true;
            entries.add("file\u0000" + relative + "\u0000" + digestFile(file));
        }
        for (var relative : CONFIG_DIRS) {
            var dir = workspace.resolve(relative);
            if (!Files.isDirectory(dir)) continue;
            var files = listFiles(dir);
            if (files.isEmpty()) continue;
            inputs = true;
            for (var file : files) {
                var path = relative + "/" + dir.relativize(file).toString().replace('\\', '/');
                entries.add("file\u0000" + path + "\u0000" + digestFile(file));
            }
        }
        entries.sort(String::compareTo);
        var digest = sha256("workspace-trust\u0000" + String.join("\n", entries));
        return new Snapshot(digest, inputs);
    }

    private static List<Path> listFiles(Path root) {
        var files = new ArrayList<Path>();
        try (var stream = Files.walk(root)) {
            stream.filter(file -> {
                if (!Files.isRegularFile(file)) return false;
                var relative = root.relativize(file);
                for (var segment : relative) {
                    if (".git".equals(segment.toString())) return false;
                }
                return true;
            }).forEach(files::add);
        } catch (IOException e) {
            LOGGER.warn("failed to list workspace trust input {}: {}", root, e.getMessage());
        }
        files.sort((a, b) -> a.toString().compareTo(b.toString()));
        return files;
    }

    private static String digestFile(Path file) {
        try {
            return sha256(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("unchecked")
    private static RecordEntry readEntry(Path trustFile, String key) {
        if (!Files.isRegularFile(trustFile)) return null;
        try {
            var root = (Map<String, Object>) MAPPER.readValue(trustFile.toFile(), Map.class);
            if (!SUPPORTED_SCHEMA_VERSIONS.contains(String.valueOf(root.get("schemaVersion")))) return null;
            var workspaces = root.get("workspaces");
            if (!(workspaces instanceof Map<?, ?> entries)) return null;
            var record = entries.get(key);
            if (!(record instanceof Map<?, ?> fields)) return null;
            if ("revoked".equals(fields.get("state"))) {
                return new RecordEntry(true, null);
            }
            var fingerprint = fields.get("fingerprint");
            return fingerprint instanceof String value ? new RecordEntry(false, value) : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ignoring unreadable workspace trust file {}: {}", trustFile, e.getMessage());
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private WorkspaceTrust() {
    }

    public enum Mode {
        CLI,
        DESKTOP_MCP
    }

    public enum State {
        TRUSTED,
        RESTRICTED
    }

    public record Migration(String legacyKey, String legacyFingerprint,
                            String desktopKey, String desktopFingerprint) {
    }

    public record Evaluation(State state, String key, String workspaceKey, String fingerprint,
                             boolean hasExecutableInputs, String reason,
                             boolean migratedFromLegacy, Migration migration) {
        public boolean trusted() {
            return state == State.TRUSTED;
        }
    }

    record RecordEntry(boolean revoked, String fingerprint) {
    }

    record Snapshot(String fingerprint, boolean hasExecutableInputs) {
    }
}
