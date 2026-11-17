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

/**
 * Workspace trust for the app-server engine.
 *
 * A workspace can contribute executable configuration: hooks, MCP servers, and skills that steer
 * the agent. The engine evaluates a fingerprint of those inputs on every boot. A fresh workspace
 * with none of them is trusted (there is nothing to execute); once any input exists, a matching
 * user-side trust record is required. A changed fingerprint invalidates the old record, so adding
 * a hook or swapping an MCP command cannot inherit trust.
 *
 * The record lives outside the workspace on purpose: a repository cannot declare itself trusted.
 */
public final class WorkspaceTrust {
    public static final String TRUST_SCHEMA_VERSION = "1";
    public static final String TRUST_FILE_NAME = "workspace-trust.json";
    public static final String RESTRICTED_PROPERTY = "core.ai.workspace.restricted";
    public static final String PLUGIN_HOME_ENV = "CORE_AI_PLUGIN_HOME";

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceTrust.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CONFIG_FILES = List.of(
            ".core-ai/agent.properties",
            ".core-ai/hooks.json",
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
        return evaluate(workspace, trustFile());
    }

    static Evaluation evaluate(Path workspace, Path trustFile) {
        var key = canonicalKey(workspace);
        var snapshot = fingerprint(workspace);
        if (!snapshot.hasExecutableInputs()) {
            return new Evaluation(State.TRUSTED, key, snapshot.fingerprint(), false, "no_workspace_config");
        }
        var record = readRecord(trustFile, key);
        if (record == null) {
            return new Evaluation(State.RESTRICTED, key, snapshot.fingerprint(), true, "not_trusted");
        }
        if (!snapshot.fingerprint().equals(record)) {
            return new Evaluation(State.RESTRICTED, key, snapshot.fingerprint(), true, "fingerprint_changed");
        }
        return new Evaluation(State.TRUSTED, key, snapshot.fingerprint(), true, null);
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
    static Snapshot fingerprint(Path workspace) {
        var entries = new ArrayList<String>();
        boolean inputs = false;
        for (var relative : CONFIG_FILES) {
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
            stream.filter(Files::isRegularFile)
                    .filter(file -> {
                        var relative = root.relativize(file);
                        for (var segment : relative) {
                            if (".git".equals(segment.toString())) return false;
                        }
                        return true;
                    })
                    .forEach(files::add);
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

    public enum State {
        TRUSTED,
        RESTRICTED
    }

    public record Evaluation(State state, String key, String fingerprint, boolean hasExecutableInputs, String reason) {
        public boolean trusted() {
            return state == State.TRUSTED;
        }
    }

    record Snapshot(String fingerprint, boolean hasExecutableInputs) {
    }
}
