package ai.core.cli.plugin;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
* author cyril
* description
* createTime  2026/4/10
**/
public final class PluginManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);

    public static final String PLUGINS_DIR_NAME = "plugins";
    public static final String DISABLED_PROPERTY = "plugin.disabled";

    public static final String PLUGIN_MANIFEST_DIR = ".claude-plugin";
    public static final String PLUGIN_MANIFEST_FILE = "plugin.json";
    public static final String SKILLS_DIR = "skills";
    public static final String COMMANDS_DIR = "commands";
    public static final String HOOKS_DIR = "hooks";
    public static final String AGENTS_DIR = "agents";

    private static volatile PluginManager instance;

    public static PluginManager getInstance(Path configDir) {
        if (instance == null) {
            synchronized (PluginManager.class) {
                if (instance == null) {
                    instance = new PluginManager(configDir);
                }
            }
        }
        return instance;
    }

    private final Path globalPluginsDir;
    private final Path localPluginsDir;
    private final Path configDir;
    private final Set<String> disabledPlugins;
    private final Map<String, Plugin> pluginCache = new ConcurrentHashMap<>();

    /** Ordered list of all loaded plugins (local-first). Populated by initializeIfNeeded. */
    private volatile List<Plugin> loadedPlugins = List.of();

    private PluginManager(Path configDir) {
        this.configDir = configDir;
        this.globalPluginsDir = Path.of(System.getProperty("user.home"), ".core-ai", PLUGINS_DIR_NAME);
        this.localPluginsDir = Path.of(".core-ai", PLUGINS_DIR_NAME);
        this.disabledPlugins = loadDisabledPlugins();
    }


    public void initializeIfNeeded(Path jarPath) {
        updateBundledPlugins(jarPath);
        loadedPlugins = loadAllPlugins();
    }

    public List<Plugin> getLoadedPlugins() {
        return loadedPlugins;
    }

    public boolean isDisabled(String pluginName) {
        return disabledPlugins.contains(pluginName);
    }


    public List<String[]> getEnabledPluginSkillSources() {
        var sources = new ArrayList<String[]>();
        for (var plugin : loadedPlugins) {
            if (disabledPlugins.contains(plugin.manifest().name())) continue;
            var skillsDir = plugin.dir().resolve(SKILLS_DIR);
            if (Files.isDirectory(skillsDir)) {
                sources.add(new String[]{plugin.manifest().name(), skillsDir.toString()});
            }
        }
        return sources;
    }

    private void updateBundledPlugins(Path jarPath) {
        var bundledPluginNames = getBundledPluginNames(jarPath);
        if (bundledPluginNames.isEmpty()) {
            LOGGER.debug("No plugins found in JAR, skipping update");
            return;
        }

        try {
            Files.createDirectories(globalPluginsDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create plugins directory: {}", e.getMessage());
            return;
        }

        var updatedPlugins = new ArrayList<String>();
        for (String pluginName : bundledPluginNames) {
            String bundledVersion = getBundledPluginVersion(jarPath, pluginName);
            String installedVersion = getInstalledPluginVersion(globalPluginsDir, pluginName);

            int cmp = compareVersions(bundledVersion, installedVersion);
            if (cmp == 0 && Files.exists(globalPluginsDir.resolve(pluginName))) {
                LOGGER.debug("Plugin '{}' up to date (version: {}), skipping", pluginName, bundledVersion);
                continue;
            }
            if (cmp < 0) {
                LOGGER.info("Plugin '{}' installed version '{}' is newer than bundled '{}', keeping installed",
                        pluginName, installedVersion, bundledVersion);
                continue;
            }

            LOGGER.info("Updating plugin '{}' from version '{}' to '{}'...",
                    pluginName, installedVersion != null ? installedVersion : "none", bundledVersion);
            pluginCache.remove(globalPluginsDir.resolve(pluginName).toString());
            extractPluginFromJar(jarPath, pluginName, globalPluginsDir);
            updatedPlugins.add(pluginName);
        }

        if (!updatedPlugins.isEmpty()) {
            setExecutablePermissions(globalPluginsDir);
            LOGGER.info("Plugins updated: {}", updatedPlugins);
        }
    }

    private List<Plugin> loadAllPlugins() {
        var plugins = new ArrayList<Plugin>();
        var seen = new HashSet<String>();
        scanDirectory(localPluginsDir, plugins, seen);
        scanDirectory(globalPluginsDir, plugins, seen);
        return Collections.unmodifiableList(plugins);
    }

    private void scanDirectory(Path pluginsDir, List<Plugin> plugins, Set<String> seen) {
        if (!Files.isDirectory(pluginsDir)) return;

        try (var stream = Files.list(pluginsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                if (seen.add(name)) {
                    loadPlugin(dir).ifPresent(plugins::add);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan plugins directory {}: {}", pluginsDir, e.getMessage());
        }
    }

    private Optional<Plugin> loadPlugin(Path dir) {
        String cacheKey = dir.toString();
        if (pluginCache.containsKey(cacheKey)) {
            return Optional.of(pluginCache.get(cacheKey));
        }
        var manifest = loadPluginManifest(dir);
        if (manifest == null) return Optional.empty();
        var plugin = new Plugin(dir, manifest, detectComponents(dir));
        pluginCache.put(cacheKey, plugin);
        return Optional.of(plugin);
    }

    private PluginManifest loadPluginManifest(Path pluginDir) {
        var manifestFile = pluginDir.resolve(PLUGIN_MANIFEST_DIR).resolve(PLUGIN_MANIFEST_FILE);
        if (!Files.exists(manifestFile)) {
            manifestFile = pluginDir.resolve(PLUGIN_MANIFEST_FILE);
        }
        if (!Files.exists(manifestFile)) return null;

        try {
            String content = Files.readString(manifestFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) JsonUtil.fromJson(Map.class, content);
            return new PluginManifest(
                    (String) map.get("name"),
                    (String) map.get("version"),
                    (String) map.get("description"),
                    (String) map.get("author"),
                    map.get("license") != null ? (String) map.get("license") : "MIT"
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to load plugin manifest from {}: {}", manifestFile, e.getMessage());
            return null;
        }
    }

    private Set<PluginComponent> detectComponents(Path pluginDir) {
        var components = EnumSet.noneOf(PluginComponent.class);
        if (Files.isDirectory(pluginDir.resolve(SKILLS_DIR))) components.add(PluginComponent.SKILLS);
        if (Files.isDirectory(pluginDir.resolve(COMMANDS_DIR))) components.add(PluginComponent.COMMANDS);
        if (Files.isDirectory(pluginDir.resolve(HOOKS_DIR))) components.add(PluginComponent.HOOKS);
        if (Files.isDirectory(pluginDir.resolve(AGENTS_DIR))) components.add(PluginComponent.AGENTS);
        if (Files.exists(pluginDir.resolve(".mcp.json"))) components.add(PluginComponent.MCP);
        if (Files.exists(pluginDir.resolve(".lsp.json"))) components.add(PluginComponent.LSP);
        return components;
    }

    private Set<String> loadDisabledPlugins() {
        var disabled = new HashSet<String>();
        var propsFile = configDir.resolve("agent.properties");
        if (!Files.exists(propsFile)) return disabled;

        try {
            String content = Files.readString(propsFile);
            for (String line : content.split("\\r?\\n")) {
                if (line.startsWith(DISABLED_PROPERTY + "=")) {
                    String value = line.substring(DISABLED_PROPERTY.length() + 1).trim();
                    if (!value.isEmpty()) {
                        for (String name : value.split(",")) {
                            disabled.add(name.trim());
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load disabled plugins: {}", e.getMessage());
        }
        return disabled;
    }

    private String getBundledPluginVersion(Path jarPath, String pluginName) {
        if (!Files.exists(jarPath)) return null;
        String manifestPath = PLUGINS_DIR_NAME + "/" + pluginName + "/" + PLUGIN_MANIFEST_DIR + "/" + PLUGIN_MANIFEST_FILE;

        try (JarInputStream jar = new JarInputStream(Files.newInputStream(jarPath))) {
            JarEntry entry = jar.getNextJarEntry();
            while (entry != null) {
                if (entry.getName().equals(manifestPath)) {
                    String content = new String(jar.readAllBytes(), StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    var map = (Map<String, Object>) JsonUtil.fromJson(Map.class, content);
                    return (String) map.get("version");
                }
                entry = jar.getNextJarEntry();
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to read bundled plugin version for '{}': {}", pluginName, e.getMessage());
        }
        return null;
    }

    private String getInstalledPluginVersion(Path pluginsDir, String pluginName) {
        var manifestFile = pluginsDir.resolve(pluginName).resolve(PLUGIN_MANIFEST_DIR).resolve(PLUGIN_MANIFEST_FILE);
        if (!Files.exists(manifestFile)) {
            manifestFile = pluginsDir.resolve(pluginName).resolve(PLUGIN_MANIFEST_FILE);
        }
        if (!Files.exists(manifestFile)) return null;

        try {
            String content = Files.readString(manifestFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) JsonUtil.fromJson(Map.class, content);
            return (String) map.get("version");
        } catch (Exception e) {
            LOGGER.debug("Failed to read installed plugin version for '{}': {}", pluginName, e.getMessage());
        }
        return null;
    }

    private Set<String> getBundledPluginNames(Path jarPath) {
        var pluginNames = new HashSet<String>();
        if (!Files.exists(jarPath)) return pluginNames;

        try (JarInputStream jar = new JarInputStream(Files.newInputStream(jarPath))) {
            JarEntry entry = jar.getNextJarEntry();
            while (entry != null) {
                String name = entry.getName();
                if (name.startsWith(PLUGINS_DIR_NAME + "/") && !PLUGINS_DIR_NAME.equals(name)) {
                    String relative = name.substring((PLUGINS_DIR_NAME + "/").length());
                    int slashIdx = relative.indexOf('/');
                    if (slashIdx > 0) {
                        pluginNames.add(relative.substring(0, slashIdx));
                    }
                }
                entry = jar.getNextJarEntry();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read bundled plugins from JAR: {}", e.getMessage());
        }
        return pluginNames;
    }

    private void extractPluginFromJar(Path jarPath, String pluginName, Path pluginsDir) {
        Path targetPluginDir = pluginsDir.resolve(pluginName);
        if (Files.exists(targetPluginDir)) {
            deleteDirectory(targetPluginDir);
        }

        try (JarInputStream jar = new JarInputStream(Files.newInputStream(jarPath))) {
            JarEntry entry = jar.getNextJarEntry();
            String prefix = PLUGINS_DIR_NAME + "/" + pluginName + "/";
            while (entry != null) {
                String name = entry.getName();
                if (name.startsWith(prefix) && !entry.isDirectory()) {
                    extractEntry(jar, entry, pluginName, pluginsDir);
                }
                entry = jar.getNextJarEntry();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to extract plugin '{}' from JAR: {}", pluginName, e.getMessage());
        }
    }

    private void extractEntry(JarInputStream jar, JarEntry entry, String pluginName, Path pluginsDir) throws IOException {
        String relativePath = entry.getName().substring((PLUGINS_DIR_NAME + "/" + pluginName + "/").length());
        Path targetPath = pluginsDir.resolve(pluginName).resolve(relativePath);
        Files.createDirectories(targetPath.getParent());

        try (OutputStream os = Files.newOutputStream(targetPath)) {
            jar.transferTo(os);
        }
    }

    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(this::deletePathQuietly);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete directory: {}", dir);
        }
    }

    private void deletePathQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete: {}", path);
        }
    }

    private void setExecutablePermissions(Path pluginsDir) {
        try (var stream = Files.walk(pluginsDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".sh"))
                  .forEach(p -> p.toFile().setExecutable(true, false));
        } catch (IOException e) {
            LOGGER.warn("Failed to set executable permissions: {}", e.getMessage());
        }
    }

    private int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        if (v1.equals(v2)) return 0;

        var parts1 = parseVersionParts(v1);
        var parts2 = parseVersionParts(v2);

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parts1[i] : 0;
            int p2 = i < parts2.length ? parts2[i] : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }

        boolean hasPreRelease1 = hasPreRelease(v1);
        boolean hasPreRelease2 = hasPreRelease(v2);
        if (hasPreRelease1 && hasPreRelease2) {
            return comparePreRelease(extractPreRelease(v1), extractPreRelease(v2));
        }
        if (hasPreRelease1 != hasPreRelease2) {
            return hasPreRelease1 ? -1 : 1;
        }
        return 0;
    }

    private int[] parseVersionParts(String version) {
        int dashIdx = version.indexOf('-');
        String numericPart = dashIdx > 0 ? version.substring(0, dashIdx) : version;
        String[] parts = numericPart.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private String extractPreRelease(String version) {
        int dashIdx = version.indexOf('-');
        return dashIdx > 0 ? version.substring(dashIdx + 1) : "";
    }

    private int comparePreRelease(String pr1, String pr2) {
        if (pr1.equals(pr2)) return 0;
        String[] parts1 = pr1.split("\\.");
        String[] parts2 = pr2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            if (i >= parts1.length) return -1;
            if (i >= parts2.length) return 1;
            String p1 = parts1[i];
            String p2 = parts2[i];
            boolean isNum1 = isNumeric(p1);
            boolean isNum2 = isNumeric(p2);
            if (isNum1 && isNum2) {
                int cmp = Integer.compare(Integer.parseInt(p1), Integer.parseInt(p2));
                if (cmp != 0) return cmp;
            } else if (isNum1) {
                return -1;
            } else if (isNum2) {
                return 1;
            } else {
                int cmp = p1.compareTo(p2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private boolean hasPreRelease(String version) {
        return version != null && version.contains("-");
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    public record Plugin(Path dir, PluginManifest manifest, Set<PluginComponent> components) {
        public boolean hasComponent(PluginComponent component) {
            return components.contains(component);
        }
    }

    public record PluginManifest(String name, String version, String description, String author, String license) { }
    public enum PluginComponent {
        SKILLS, COMMANDS, HOOKS, AGENTS, MCP, LSP
    }
}
