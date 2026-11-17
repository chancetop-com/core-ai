package ai.core.cli.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Skill roots a desktop host granted to this engine.
 *
 * The desktop resolves its plugin store and injects only active, granted packages through
 * {@code CORE_AI_PLUGIN_SKILL_ROOTS}. This class validates the paths again before the engine reads
 * them: absolute, inside the managed package store, no traversal and no symlinked escape. A
 * malformed payload yields no roots (fail closed) rather than a best-effort guess.
 */
public final class PluginSkillRoots {
    public static final String ROOTS_ENV = "CORE_AI_PLUGIN_SKILL_ROOTS";
    public static final String STORE_ROOT_ENV = "CORE_AI_PLUGIN_STORE_ROOT";
    public static final String STATE_FILE_ENV = "CORE_AI_PLUGIN_STATE_FILE";

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginSkillRoots.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final int MAX_ROOTS = 50;

    public static List<Root> fromEnvironment() {
        if (Boolean.getBoolean("core.ai.workspace.restricted")) return List.of();
        var storeRoot = System.getenv(STORE_ROOT_ENV);
        var packagesRoot = storeRoot != null && !storeRoot.isBlank()
                ? Path.of(storeRoot)
                : Path.of(System.getProperty("user.home"), ".core-ai", "plugin-store", "packages");
        return parse(System.getenv(ROOTS_ENV), packagesRoot);
    }

    static List<Root> parse(String json, Path packagesRoot) {
        if (json == null || json.isBlank() || packagesRoot == null) return List.of();
        Path root;
        try {
            root = packagesRoot.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return List.of();
        }
        Map<String, Root> roots = new LinkedHashMap<>();
        try {
            var node = MAPPER.readTree(json);
            if (!node.isArray()) return List.of();
            for (var entry : node) {
                if (roots.size() >= MAX_ROOTS) break;
                if (!entry.isObject()) continue;
                var name = entry.path("name").asText("");
                var dir = entry.path("dir").asText("");
                var digest = entry.path("digest").asText("");
                var grantEpoch = entry.path("grantEpoch").asLong(-1);
                var validated = validate(root, name, dir, digest, grantEpoch);
                if (validated != null) roots.putIfAbsent(validated.dir(), validated);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("ignoring invalid plugin skill roots payload: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(new ArrayList<>(roots.values()));
    }

    private static Root validate(Path packagesRoot, String name, String dir, String digest, long grantEpoch) {
        if (!SAFE_NAME.matcher(name).matches()) {
            LOGGER.warn("ignoring plugin skill root with invalid name: {}", name);
            return null;
        }
        if (!DIGEST.matcher(digest).matches() || grantEpoch < 0) {
            LOGGER.warn("ignoring plugin skill root with invalid digest or epoch: {}", name);
            return null;
        }
        Path candidate;
        try {
            candidate = Path.of(dir).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            LOGGER.warn("ignoring plugin skill root with invalid path: {}", dir);
            return null;
        }
        if (!candidate.startsWith(packagesRoot) || candidate.equals(packagesRoot)) {
            LOGGER.warn("ignoring plugin skill root outside the package store: {}", candidate);
            return null;
        }
        if (!Files.isDirectory(candidate)) {
            LOGGER.warn("ignoring missing plugin skill root: {}", candidate);
            return null;
        }
        try {
            var realRoot = packagesRoot.toRealPath();
            var realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot) || realCandidate.equals(realRoot)) {
                LOGGER.warn("ignoring plugin skill root escaping the package store via a link: {}", candidate);
                return null;
            }
            return new Root(name, realCandidate.toString(), digest, grantEpoch);
        } catch (IOException e) {
            LOGGER.warn("ignoring unresolvable plugin skill root: {}", candidate);
            return null;
        }
    }

    private PluginSkillRoots() {
    }

    public record Root(String name, String dir, String digest, long grantEpoch) {
    }
}
