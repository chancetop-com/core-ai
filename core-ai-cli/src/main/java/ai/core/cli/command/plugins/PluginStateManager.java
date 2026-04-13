package ai.core.cli.command.plugins;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages plugin enable/disable state persistence.
 */
public class PluginStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginStateManager.class);
    private static final String DISABLED_PROPERTY = "plugin.disabled";

    private final TerminalUI ui;
    private final Path configDir;
    private final PluginDiscovery discovery;

    public PluginStateManager(TerminalUI ui, PluginDiscovery discovery) {
        this.ui = ui;
        this.configDir = Path.of(System.getProperty("user.home"), ".core-ai");
        this.discovery = discovery;
    }

    /**
     * Enable a plugin (remove from disabled list).
     */
    public void enable(String pluginName) {
        String nameToEnable = pluginName;
        if (pluginName == null || pluginName.isBlank()) {
            var plugins = discovery.scanPlugins();
            var disabledPlugins = loadDisabledPlugins();
            if (plugins.isEmpty()) {
                ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No plugins to enable.\n\n" + AnsiTheme.RESET);
                return;
            }

            var labels = new ArrayList<String>();
            var disabledPluginEntries = new ArrayList<PluginDiscovery.PluginEntry>();
            for (var plugin : plugins) {
                if (disabledPlugins.contains(plugin.name())) {
                    disabledPluginEntries.add(plugin);
                    labels.add(plugin.name() + AnsiTheme.MUTED + " (" + plugin.version() + ")" + AnsiTheme.RESET);
                }
            }

            if (labels.isEmpty()) {
                ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "All plugins are already enabled.\n\n" + AnsiTheme.RESET);
                return;
            }

            ui.printStreamingChunk(String.format("\n  %sSelect plugin to enable:%s%n", AnsiTheme.PROMPT, AnsiTheme.RESET));
            int selected = ui.pickIndex(labels);
            if (selected < 0) return;

            nameToEnable = disabledPluginEntries.get(selected).name();
        }

        var disabledPlugins = loadDisabledPlugins();
        disabledPlugins.remove(nameToEnable);
        saveDisabledPlugins(disabledPlugins);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Enabled " + nameToEnable + "\n\n");
    }

    /**
     * Disable a plugin (add to disabled list).
     */
    public void disable(String pluginName) {
        String nameToDisable = pluginName;
        if (pluginName == null || pluginName.isBlank()) {
            var plugins = discovery.scanPlugins();
            var disabledPlugins = loadDisabledPlugins();
            if (plugins.isEmpty()) {
                ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No plugins to disable.\n\n" + AnsiTheme.RESET);
                return;
            }

            var labels = new ArrayList<String>();
            var enabledPluginEntries = new ArrayList<PluginDiscovery.PluginEntry>();
            for (var plugin : plugins) {
                if (!disabledPlugins.contains(plugin.name())) {
                    enabledPluginEntries.add(plugin);
                    labels.add(plugin.name() + AnsiTheme.MUTED + " (" + plugin.version() + ")" + AnsiTheme.RESET);
                }
            }

            if (labels.isEmpty()) {
                ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No enabled plugins.\n\n" + AnsiTheme.RESET);
                return;
            }

            ui.printStreamingChunk(String.format("\n  %sSelect plugin to disable:%s%n", AnsiTheme.PROMPT, AnsiTheme.RESET));
            int selected = ui.pickIndex(labels);
            if (selected < 0) return;

            nameToDisable = enabledPluginEntries.get(selected).name();
        }

        var disabledPlugins = loadDisabledPlugins();
        disabledPlugins.add(nameToDisable);
        saveDisabledPlugins(disabledPlugins);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Disabled " + nameToDisable + "\n\n");
    }

    /**
     * Load disabled plugins from ~/.core-ai/agent.properties
     * Format: plugin.disabled=plugin1,plugin2,plugin3
     */
    public Set<String> loadDisabledPlugins() {
        var disabled = new HashSet<String>();
        var propsFile = configDir.resolve("agent.properties");

        if (!Files.exists(propsFile)) {
            return disabled;
        }

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
            // ignore read errors
            LOGGER.debug("Failed to load disabled plugins: {}", e.getMessage());
        }
        return disabled;
    }

    /**
     * Save disabled plugins to ~/.core-ai/agent.properties
     */
    public void saveDisabledPlugins(Set<String> disabled) {
        var propsFile = configDir.resolve("agent.properties");

        try {
            String content = Files.exists(propsFile) ? Files.readString(propsFile) : "";

            StringBuilder sb = new StringBuilder();
            boolean propertyExists = false;

            for (String line : content.split("\\r?\\n")) {
                if (line.startsWith(DISABLED_PROPERTY + "=")) {
                    propertyExists = true;
                    if (disabled.isEmpty()) {
                        sb.append("# ").append(line).append('\n');
                    } else {
                        sb.append(DISABLED_PROPERTY).append('=').append(String.join(",", disabled)).append('\n');
                    }
                } else {
                    sb.append(line).append('\n');
                }
            }

            if (!propertyExists && !disabled.isEmpty()) {
                sb.append(DISABLED_PROPERTY).append('=').append(String.join(",", disabled)).append('\n');
            }

            Files.writeString(propsFile, sb.toString().trim() + "\n");
        } catch (IOException e) {
            ui.showError("Failed to save disabled plugins: " + e.getMessage());
        }
    }
}
