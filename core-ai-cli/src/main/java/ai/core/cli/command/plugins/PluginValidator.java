package ai.core.cli.command.plugins;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates plugin structure and manifest files.
 */
public class PluginValidator {

    private static final String[] COMPONENT_DIRS = {"commands", "skills", "agents", "hooks"};

    private final TerminalUI ui;
    private final PluginDiscovery discovery;

    public PluginValidator(TerminalUI ui, PluginDiscovery discovery) {
        this.ui = ui;
        this.discovery = discovery;
    }

    /**
     * Validate a plugin at the given path.
     */
    public void validate(String pathStr) {
        Path pluginPath;
        if (pathStr == null || pathStr.isBlank()) {
            pluginPath = Path.of(".");
        } else {
            pluginPath = Path.of(pathStr);
            if (!pluginPath.isAbsolute()) {
                pluginPath = Path.of(System.getProperty("user.dir")).resolve(pluginPath);
            }
        }

        if (!Files.exists(pluginPath)) {
            ui.showError("Path not found: " + pathStr);
            return;
        }

        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Validating plugin at: " + pluginPath + "...\n" + AnsiTheme.RESET);

        var manifest = discovery.loadPluginManifest(pluginPath);
        if (manifest == null) {
            ui.showError("Invalid: plugin.json not found or invalid");
            return;
        }

        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        if (manifest.name() == null || manifest.name().isBlank()) {
            errors.add("Missing required field: name");
        }

        for (String dirName : COMPONENT_DIRS) {
            checkComponentDir(pluginPath, dirName, errors, warnings);
        }

        checkFile(pluginPath.resolve(".mcp.json"), false, warnings);
        checkFile(pluginPath.resolve(".lsp.json"), false, warnings);

        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + manifest.name() + " v" + manifest.version() + ":" + AnsiTheme.RESET + "\n");

        for (var error : errors) {
            ui.printStreamingChunk("  " + AnsiTheme.ERROR + "✗ " + error + AnsiTheme.RESET + "\n");
        }

        for (var warning : warnings) {
            ui.printStreamingChunk("  " + AnsiTheme.WARNING + "! " + warning + AnsiTheme.RESET + "\n");
        }

        if (errors.isEmpty()) {
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓ Plugin is valid" + AnsiTheme.RESET + "\n\n");
        } else {
            ui.printStreamingChunk("  " + AnsiTheme.ERROR + "✗ Plugin has " + errors.size() + " error(s)" + AnsiTheme.RESET + "\n\n");
        }
    }

    @SuppressWarnings("unused")
    private void checkComponentDir(Path pluginDir, String dirName, List<String> errors, List<String> warnings) {
        var dir = pluginDir.resolve(dirName);
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                long count = stream.count();
                if (count == 0) {
                    warnings.add(dirName + "/ is empty");
                }
            } catch (IOException ignored) {
                // unable to list directory contents
            }
        }
    }

    @SuppressWarnings("unused")
    private void checkFile(Path file, boolean required, List<String> warnings) {
        if (Files.exists(file)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                JsonUtil.fromJson(Map.class, content);
            } catch (Exception e) {
                warnings.add(file.getFileName() + " is not valid JSON");
            }
        }
    }
}
