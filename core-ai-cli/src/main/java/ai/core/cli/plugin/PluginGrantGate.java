package ai.core.cli.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-call authorization for desktop-managed plugin skills.
 *
 * The desktop injects the active roots with the plugin id, digest and grant epoch it observed at
 * engine start. Before a skill is read, this gate re-reads the shared state file and checks that the
 * install is still enabled, still points at the same digest, still grants {@code engine.skills} and
 * every declared required capability, and that the grant epoch did not move backwards. A revoke or
 * disable therefore takes effect on the next skill call without waiting for an engine restart.
 *
 * This is a cooperative process boundary: it protects the normal engine/desktop paths, not arbitrary
 * same-user OS code (documented limitation in design §5.6).
 */
public final class PluginGrantGate {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginGrantGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "user";
    private static final String ENGINE_SKILLS = "engine.skills";

    public static PluginGrantGate fromEnvironment() {
        if (Boolean.getBoolean("core.ai.workspace.restricted")) {
            return new PluginGrantGate(List.of(), null);
        }
        var roots = PluginSkillRoots.fromEnvironment().stream()
                .map(root -> new Root(root.name(), root.dir(), root.digest(), root.grantEpoch()))
                .toList();
        var stateFile = System.getenv(PluginSkillRoots.STATE_FILE_ENV);
        return new PluginGrantGate(roots, stateFile == null || stateFile.isBlank() ? null : Path.of(stateFile));
    }

    static PluginGrantGate of(List<Root> roots, Path stateFile) {
        return new PluginGrantGate(roots, stateFile);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        var result = new ArrayList<String>();
        for (var item : list) {
            if (item instanceof String text) result.add(text);
        }
        return result;
    }

    private final List<Root> roots;
    private final Path stateFile;

    private PluginGrantGate(List<Root> roots, Path stateFile) {
        this.roots = roots;
        this.stateFile = stateFile;
    }

    public boolean empty() {
        return roots.isEmpty();
    }

    /** True for non-plugin skills; for a plugin skill, true only while its install stays authorized. */
    public boolean permits(String skillDir) {
        if (empty()) return true;
        if (skillDir == null) return true;
        Path target;
        try {
            target = Path.of(skillDir).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return false;
        }
        Root owner = ownerOf(target);
        if (owner == null) return true;
        return authorized(owner);
    }

    private Root ownerOf(Path target) {
        Root owner = null;
        for (var root : roots) {
            Path rootPath;
            try {
                rootPath = Path.of(root.dir()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (!target.startsWith(rootPath)) continue;
            if (owner == null || rootPath.getNameCount() > Path.of(owner.dir()).getNameCount()) owner = root;
        }
        return owner;
    }

    @SuppressWarnings("unchecked")
    private boolean authorized(Root root) {
        try {
            if (!Files.isRegularFile(stateFile)) return false;
            var state = (Map<String, Object>) MAPPER.readValue(stateFile.toFile(), Map.class);
            var grantEpoch = state.get("grantEpoch");
            if (grantEpoch instanceof Number epoch && epoch.longValue() < root.grantEpoch()) {
                LOGGER.warn("plugin state rolled back below the engine epoch, pluginId={}", root.name());
                return false;
            }
            var installs = state.get("installs");
            if (!(installs instanceof List<?> list)) return false;
            Map<?, ?> match = null;
            for (var item : list) {
                if (!(item instanceof Map<?, ?> install)) continue;
                if (!SCOPE.equals(String.valueOf(install.get("scope")))) continue;
                if (!root.name().equals(String.valueOf(install.get("pluginId")))) continue;
                if (!Boolean.TRUE.equals(install.get("enabled"))) return false;
                if (!root.digest().equals(String.valueOf(install.get("digest")))) return false;
                match = install;
            }
            if (match == null) return false;
            var granted = stringList(match.get("granted"));
            if (!granted.contains(ENGINE_SKILLS)) return false;
            var capabilities = capabilitiesOf(state, root.digest());
            if (capabilities == null) return false;
            return granted.containsAll(capabilities.required());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("plugin grant check failed, pluginId={}, error={}", root.name(), e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Capabilities capabilitiesOf(Map<String, Object> state, String digest) {
        var packages = state.get("packages");
        if (!(packages instanceof Map<?, ?> map)) return null;
        var entry = map.get(digest);
        if (!(entry instanceof Map<?, ?> pkg)) return null;
        var required = stringList(pkg.get("requiredCapabilities"));
        if (!required.isEmpty()) return new Capabilities(required);
        // Older state files did not store the requirement list; read it from the immutable package
        // manifest instead of failing open.
        return new Capabilities(requiredFromManifest(digest));
    }

    private List<String> requiredFromManifest(String digest) {
        if (stateFile == null) return List.of();
        var parent = stateFile.getParent();
        if (parent == null) return List.of();
        var packageDir = parent.resolve("packages").resolve(digest);
        for (var relative : List.of(".claude-plugin/plugin.json", "plugin.json")) {
            var manifest = packageDir.resolve(relative);
            if (!Files.isRegularFile(manifest)) continue;
            try {
                var root = MAPPER.readTree(manifest.toFile());
                var capabilities = root.path("coreai").path("capabilities").path("required");
                if (!capabilities.isArray()) return List.of();
                var result = new ArrayList<String>();
                for (var item : capabilities) {
                    if (item.isTextual()) result.add(item.asText());
                }
                return result;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("failed to read plugin manifest for grant check, digest={}", digest);
                return List.of();
            }
        }
        return List.of();
    }

    public record Root(String name, String dir, String digest, long grantEpoch) {
    }

    private record Capabilities(List<String> required) {
    }
}
