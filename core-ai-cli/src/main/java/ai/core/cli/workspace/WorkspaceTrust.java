package ai.core.cli.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workspace trust for the app-server engine.
 *
 * A workspace can contribute executable configuration: hooks, MCP servers, and skills that steer
 * the agent. The engine evaluates a fingerprint of those inputs on every boot. A fresh workspace
 * with none of them is trusted (there is nothing to execute); once any input exists, a matching
 * user-side trust record is required. A changed fingerprint invalidates the old record, so adding
 * a hook or swapping an MCP command cannot inherit trust.
 *
 * The desktop-managed MCP mode uses its own fingerprint (CLI MCP files are not loaded there) and
 * its own trust record key ({@code <canonical>#desktop-mcp}). An existing CLI record is migrated
 * once, but only when it matches the full legacy fingerprint.
 *
 * The record lives outside the workspace on purpose: a repository cannot declare itself trusted.
 */
public final class WorkspaceTrust {
    public static final String TRUST_SCHEMA_VERSION = "1";
    public static final String TRUST_FILE_NAME = "workspace-trust.json";
    public static final String RESTRICTED_PROPERTY = "core.ai.workspace.restricted";
    public static final String PLUGIN_HOME_ENV = "CORE_AI_PLUGIN_HOME";
    public static final String DESKTOP_MCP_KEY_SUFFIX = "#desktop-mcp";

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceTrust.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object WRITE_LOCK = new Object();
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
        if (!snapshot.hasExecutableInputs()) {
            return new Evaluation(State.TRUSTED, key, workspaceKey, snapshot.fingerprint(), false, "no_workspace_config");
        }
        var record = readRecord(trustFile, key);
        if (record == null && mode == Mode.DESKTOP_MCP) {
            var migrated = migrateLegacyRecord(trustFile, workspace, workspaceKey, key, snapshot.fingerprint());
            if (migrated) {
                return new Evaluation(State.TRUSTED, key, workspaceKey, snapshot.fingerprint(), true, "migrated_from_cli_record");
            }
        }
        if (record == null) {
            return new Evaluation(State.RESTRICTED, key, workspaceKey, snapshot.fingerprint(), true, "not_trusted");
        }
        if (!snapshot.fingerprint().equals(record)) {
            return new Evaluation(State.RESTRICTED, key, workspaceKey, snapshot.fingerprint(), true, "fingerprint_changed");
        }
        return new Evaluation(State.TRUSTED, key, workspaceKey, snapshot.fingerprint(), true, null);
    }

    /**
     * A desktop-managed workspace used to be evaluated with the CLI fingerprint. Reuse that record
     * only when it still matches the full legacy fingerprint (including CLI MCP files).
     */
    private static boolean migrateLegacyRecord(Path trustFile, Path workspace, String workspaceKey,
                                               String desktopKey, String desktopFingerprint) {
        var legacyRecord = readRecord(trustFile, workspaceKey);
        if (legacyRecord == null) return false;
        var legacySnapshot = fingerprint(workspace, Mode.CLI);
        if (!legacySnapshot.fingerprint().equals(legacyRecord)) return false;
        writeRecord(trustFile, desktopKey, desktopFingerprint);
        LOGGER.info("migrated workspace trust record to the desktop-mcp key");
        return true;
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

    /** Fingerprint covers every workspace input that can execute or steer the agent. */
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
            // An unreadable input is not silently ignored: it gets its own marker so any later
            // readability change also changes the fingerprint.
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("unchecked")
    private static String readRecord(Path trustFile, String key) {
        if (!Files.isRegularFile(trustFile)) return null;
        try {
            var root = (Map<String, Object>) MAPPER.readValue(trustFile.toFile(), Map.class);
            if (!TRUST_SCHEMA_VERSION.equals(String.valueOf(root.get("schemaVersion")))) return null;
            var workspaces = root.get("workspaces");
            if (!(workspaces instanceof Map<?, ?> entries)) return null;
            var record = entries.get(key);
            if (!(record instanceof Map<?, ?> fields)) return null;
            var fingerprint = fields.get("fingerprint");
            return fingerprint instanceof String value ? value : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ignoring unreadable workspace trust file {}: {}", trustFile, e.getMessage());
            return null;
        }
    }

    /** Read-modify-write of one record; concurrent writers are rare (boot-time migration only). */
    @SuppressWarnings("unchecked")
    private static void writeRecord(Path trustFile, String key, String fingerprint) {
        synchronized (WRITE_LOCK) {
            try {
                Map<String, Object> root;
                if (Files.isRegularFile(trustFile)) {
                    root = MAPPER.readValue(trustFile.toFile(), Map.class);
                } else {
                    root = new LinkedHashMap<>();
                }
                if (!TRUST_SCHEMA_VERSION.equals(String.valueOf(root.get("schemaVersion")))) {
                    root = new LinkedHashMap<>();
                    root.put("schemaVersion", TRUST_SCHEMA_VERSION);
                }
                Map<String, Object> workspaces;
                if (root.get("workspaces") instanceof Map<?, ?> existing) {
                    workspaces = new LinkedHashMap<>((Map<String, Object>) existing);
                } else {
                    workspaces = new LinkedHashMap<>();
                }
                var record = new LinkedHashMap<String, Object>();
                record.put("fingerprint", fingerprint);
                record.put("trustedAt", Instant.now().toString());
                workspaces.put(key, record);
                root.put("workspaces", workspaces);
                var parent = trustFile.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                var temp = trustFile.resolveSibling(TRUST_FILE_NAME + ".tmp-" + ProcessHandle.current().pid());
                Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                        StandardCharsets.UTF_8);
                moveIntoPlace(temp, trustFile);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("failed to persist the migrated workspace trust record: {}", e.getMessage());
            }
        }
    }

    private static void moveIntoPlace(Path temp, Path trustFile) throws IOException {
        try {
            Files.move(temp, trustFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, trustFile, StandardCopyOption.REPLACE_EXISTING);
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

    public record Evaluation(State state, String key, String workspaceKey, String fingerprint,
                             boolean hasExecutableInputs, String reason) {
        public boolean trusted() {
            return state == State.TRUSTED;
        }
    }

    record Snapshot(String fingerprint, boolean hasExecutableInputs) {
    }
}
