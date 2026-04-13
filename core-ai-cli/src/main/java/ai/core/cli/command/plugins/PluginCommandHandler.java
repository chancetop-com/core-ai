package ai.core.cli.command.plugins;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Handles /plugins command for managing Claude-style plugins.
 *
 * Supports:
 * - /plugins           - Interactive plugin browser
 * - /plugins list      - List installed plugins
 * - /plugins install   - Install from marketplace/git/npm
 * - /plugins uninstall - Remove plugin
 * - /plugins enable    - Enable plugin
 * - /plugins disable   - Disable plugin
 * - /plugins validate  - Validate plugin structure
 */
public class PluginCommandHandler {

    private static final Path USER_PLUGINS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "plugins");

    private final TerminalUI ui;
    private final PluginDiscovery discovery;
    private final PluginInstaller installer;
    private final PluginValidator validator;
    private final PluginStateManager stateManager;

    public PluginCommandHandler(TerminalUI ui) {
        this.ui = ui;
        this.discovery = new PluginDiscovery();
        this.installer = new PluginInstaller(ui, discovery);
        this.validator = new PluginValidator(ui, discovery);
        this.stateManager = new PluginStateManager(ui, discovery);
    }

    public void handle() {
        interactivePluginBrowser();
    }

    public void handle(String args) {
        if (args == null || args.isBlank()) {
            handle();
            return;
        }

        var parts = args.trim().split("\\s+", 2);
        var subcommand = parts[0].toLowerCase(Locale.ROOT);
        var subArgs = parts.length > 1 ? parts[1].trim() : "";

        switch (subcommand) {
            case "list", "ls" -> listPlugins();
            case "install", "add" -> installer.install(subArgs);
            case "uninstall", "remove", "rm" -> installer.uninstall(subArgs);
            case "enable" -> stateManager.enable(subArgs);
            case "disable" -> stateManager.disable(subArgs);
            case "validate" -> validator.validate(subArgs);
            case "info" -> showPluginInfo(subArgs);
            case "reload" -> reloadPlugins();
            case "help", "?" -> showHelp();
            default -> {
                ui.printStreamingChunk("\n  " + AnsiTheme.ERROR + "Unknown subcommand: " + subcommand + AnsiTheme.RESET + "\n");
                showHelp();
            }
        }
    }

    private void interactivePluginBrowser() {
        while (true) {
            var plugins = discovery.scanPlugins();
            var disabledPlugins = stateManager.loadDisabledPlugins();

            ui.printStreamingChunk("\n");
            ui.printStreamingChunk(String.format("  %sPlugins (%d)%s%n", AnsiTheme.PROMPT, plugins.size(), AnsiTheme.RESET));
            ui.printStreamingChunk("  " + "-".repeat(50) + "\n");

            if (plugins.isEmpty()) {
                ui.printStreamingChunk("  " + AnsiTheme.MUTED + "No plugins installed.\n" + AnsiTheme.RESET);
                ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Use /plugins install <source> to add one.\n\n" + AnsiTheme.RESET);
                return;
            }

            var labels = new ArrayList<String>();
            var enabledEntries = new ArrayList<PluginDiscovery.PluginEntry>();
            var disabledEntries = new ArrayList<PluginDiscovery.PluginEntry>();

            for (var plugin : plugins) {
                boolean isDisabled = disabledPlugins.contains(plugin.name());
                if (isDisabled) {
                    disabledEntries.add(plugin);
                } else {
                    enabledEntries.add(plugin);
                }
            }

            for (var plugin : enabledEntries) {
                labels.add(AnsiTheme.SUCCESS + "● " + AnsiTheme.RESET + AnsiTheme.PROMPT + plugin.name() + AnsiTheme.RESET
                    + AnsiTheme.MUTED + " v" + plugin.version() + " (enabled)" + AnsiTheme.RESET);
            }
            for (var plugin : disabledEntries) {
                labels.add(AnsiTheme.WARNING + "○ " + AnsiTheme.RESET + AnsiTheme.MUTED + plugin.name() + AnsiTheme.RESET
                    + AnsiTheme.MUTED + " v" + plugin.version() + " (disabled)" + AnsiTheme.RESET);
            }
            labels.add(AnsiTheme.MUTED + "Back" + AnsiTheme.RESET);

            int selected = ui.pickIndex(labels);
            if (selected < 0 || selected >= plugins.size()) {
                return;
            }

            var selectedPlugin = plugins.get(selected);
            showPluginActions(selectedPlugin);
        }
    }

    private void showPluginActions(PluginDiscovery.PluginEntry plugin) {
        var disabledPlugins = stateManager.loadDisabledPlugins();
        boolean isDisabled = disabledPlugins.contains(plugin.name());

        while (true) {
            var status = isDisabled
                ? AnsiTheme.WARNING + "disabled" + AnsiTheme.RESET
                : AnsiTheme.SUCCESS + "enabled" + AnsiTheme.RESET;

            ui.printStreamingChunk("\n");
            ui.printStreamingChunk("  " + AnsiTheme.PROMPT + plugin.name() + AnsiTheme.RESET
                + AnsiTheme.MUTED + " v" + plugin.version() + AnsiTheme.RESET + " - " + status + "\n");
            if (plugin.description() != null && !plugin.description().isBlank()) {
                ui.printStreamingChunk("  " + AnsiTheme.MUTED + plugin.description() + AnsiTheme.RESET + "\n");
            }
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Location: " + plugin.path() + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk("  " + "-".repeat(50) + "\n");

            var options = new ArrayList<String>();
            if (isDisabled) {
                options.add("Enable plugin");
            } else {
                options.add("Disable plugin");
            }
            options.add("Show info");
            options.add(AnsiTheme.MUTED + "Back to list" + AnsiTheme.RESET);

            int selected = ui.pickIndex(options);
            if (selected < 0 || selected >= options.size() - 1) {
                return;
            }

            switch (selected) {
                case 0 -> {
                    if (isDisabled) {
                        disabledPlugins.remove(plugin.name());
                        stateManager.saveDisabledPlugins(disabledPlugins);
                        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                            + " Plugin enabled - restart to apply changes.\n");
                    } else {
                        disabledPlugins.add(plugin.name());
                        stateManager.saveDisabledPlugins(disabledPlugins);
                        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                            + " Plugin disabled - restart to apply changes.\n");
                    }
                    isDisabled = !isDisabled;
                }
                case 1 -> showPluginDetails(plugin);
                default -> { }
            }
        }
    }

    private void showPluginDetails(PluginDiscovery.PluginEntry plugin) {
        var disabledPlugins = stateManager.loadDisabledPlugins();
        boolean isDisabled = disabledPlugins.contains(plugin.name());

        ui.printStreamingChunk("\n");
        ui.printStreamingChunk("  " + AnsiTheme.PROMPT + plugin.name() + AnsiTheme.RESET + " v" + plugin.version() + "\n");
        ui.printStreamingChunk("  " + "-".repeat(50) + "\n");
        ui.printStreamingChunk("  Status: " + (isDisabled ? AnsiTheme.WARNING + "disabled" : AnsiTheme.SUCCESS + "enabled") + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("  Location: " + AnsiTheme.MUTED + plugin.path() + AnsiTheme.RESET + "\n");

        if (plugin.description() != null) {
            ui.printStreamingChunk("  Description: " + AnsiTheme.MUTED + plugin.description() + AnsiTheme.RESET + "\n");
        }

        var components = new ArrayList<String>();
        if (Files.exists(plugin.path().resolve("commands"))) components.add("commands");
        if (Files.exists(plugin.path().resolve("skills"))) components.add("skills");
        if (Files.exists(plugin.path().resolve("agents"))) components.add("agents");
        if (Files.exists(plugin.path().resolve("hooks"))) components.add("hooks");
        if (Files.exists(plugin.path().resolve(".mcp.json"))) components.add("MCP");

        if (!components.isEmpty()) {
            ui.printStreamingChunk("  Components: " + String.join(", ", components) + "\n");
        }

        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Press Enter to continue..." + AnsiTheme.RESET);
        ui.readRawLine();
    }

    public void listPlugins() {
        var plugins = discovery.scanPlugins();
        if (plugins.isEmpty()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No plugins installed." + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Use /plugins install <source> to add one.\n\n" + AnsiTheme.RESET);
            return;
        }

        var disabledPlugins = stateManager.loadDisabledPlugins();

        ui.printStreamingChunk(String.format("\n  %sInstalled Plugins (%d)%s%n", AnsiTheme.PROMPT, plugins.size(), AnsiTheme.RESET));
        for (var plugin : plugins) {
            boolean enabled = !disabledPlugins.contains(plugin.name());
            var status = enabled ? AnsiTheme.SUCCESS + "✓" : AnsiTheme.WARNING + "○";
            var statusStr = enabled ? "enabled" : "disabled";

            ui.printStreamingChunk(buildPluginLine(status, statusStr, plugin));
        }
        ui.printStreamingChunk("\n");
    }

    private String buildPluginLine(String status, String statusStr, PluginDiscovery.PluginEntry plugin) {
        String versionInfo = plugin.version() != null ? AnsiTheme.MUTED + " v" + plugin.version() + AnsiTheme.RESET : "";
        String descInfo = (plugin.description() != null && !plugin.description().isBlank()) ? " - " + truncate(plugin.description(), 50) : "";
        return "  " + status + ' ' + AnsiTheme.RESET + AnsiTheme.PROMPT + plugin.name() + AnsiTheme.RESET
            + versionInfo + descInfo + AnsiTheme.MUTED + '[' + statusStr + ']' + AnsiTheme.RESET + "\n";
    }

    public void showPluginInfo(String name) {
        if (name == null || name.isBlank()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET + " Please specify a plugin name.\n");
            return;
        }

        var pluginDir = USER_PLUGINS_DIR.resolve(name);
        if (!Files.exists(pluginDir)) {
            ui.showError("Plugin not found: " + name);
            return;
        }

        var manifest = discovery.loadPluginManifest(pluginDir);
        if (manifest == null) {
            ui.showError("Invalid plugin: manifest not found");
            return;
        }

        var disabledPlugins = stateManager.loadDisabledPlugins();
        boolean enabled = !disabledPlugins.contains(name);

        ui.printStreamingChunk("""
            
              %s%s%s v%s%n
              %s%s%n%n
              %sLocation:%s %s%n
              %sStatus:%s %s%n
            """.formatted(
                AnsiTheme.PROMPT, manifest.name(), AnsiTheme.RESET,
                manifest.version() != null ? manifest.version() : "unknown",
                AnsiTheme.MUTED, manifest.description() != null ? manifest.description() : "No description",
                AnsiTheme.MUTED, AnsiTheme.RESET, pluginDir,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                enabled ? AnsiTheme.SUCCESS + "enabled" : AnsiTheme.WARNING + "disabled"
        ));

        var components = new ArrayList<String>();
        if (Files.exists(pluginDir.resolve("commands"))) components.add("commands");
        if (Files.exists(pluginDir.resolve("skills"))) components.add("skills");
        if (Files.exists(pluginDir.resolve("agents"))) components.add("agents");
        if (Files.exists(pluginDir.resolve("hooks"))) components.add("hooks");
        if (Files.exists(pluginDir.resolve(".mcp.json"))) components.add("MCP servers");
        if (Files.exists(pluginDir.resolve(".lsp.json"))) components.add("LSP servers");

        if (!components.isEmpty()) {
            ui.printStreamingChunk("  " + AnsiTheme.PROMPT + "Components:" + AnsiTheme.RESET + "\n");
            for (var comp : components) {
                ui.printStreamingChunk("    • " + comp + "\n");
            }
        }
        ui.printStreamingChunk("\n");
    }

    public void reloadPlugins() {
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Reloading plugins..." + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓ Plugins reloaded\n\n");
    }

    private void showHelp() {
        ui.printStreamingChunk("""

              %s/plugins %s- Claude-style plugin management%n%n
              %sUsage:%s
                /plugins list              %s- List installed plugins%s
                /plugins install <source> [--local|--global]  %s- Install plugin%s
                /plugins uninstall <name>  %s- Remove plugin%s
                /plugins enable <name>    %s- Enable plugin%s
                /plugins disable <name>    %s- Disable plugin%s
                /plugins validate [path]   %s- Validate plugin structure%s
                /plugins info <name>       %s- Show plugin details%s
                /plugins reload           %s- Reload all plugins%s%n
              %sScope:%s
                --local   %s- Install to .core-ai/plugins/ (project)%s
                --global  %s- Install to ~/.core-ai/plugins/ (user, default)%s%n
              %sSources:%s
                git:<url>                  %s- Git repository%s
                npm:<package>              %s- NPM package (with optional registry)%
                ./path                     %s- Local directory%s
                github:<owner/repo>        %s- GitHub shorthand%s%n
              %sExamples:%s
                /plugins install git:https://github.com/org/my-plugin --global
                /plugins install npm:@my-org/my-skill --local
                /plugins install ./my-local-plugin%n%n
            """.formatted(
                AnsiTheme.PROMPT, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET,
                AnsiTheme.MUTED, AnsiTheme.RESET
            ));
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
