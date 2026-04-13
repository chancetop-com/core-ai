package ai.core.cli.command.plugins;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Handles plugin installation from various sources (local, git, npm).
 */
public class PluginInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginInstaller.class);
    private static final Path USER_PLUGINS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "plugins");
    private static final Path LOCAL_PLUGINS_DIR = Path.of(".core-ai", "plugins");

    private final TerminalUI ui;
    private final PluginDiscovery discovery;

    public PluginInstaller(TerminalUI ui, PluginDiscovery discovery) {
        this.ui = ui;
        this.discovery = discovery;
    }

    /**
     * Install a plugin from the given source.
     */
    public void install(String args) {
        if (args == null || args.isBlank()) {
            showUsage();
            return;
        }

        var parsed = parseArgs(args);
        if (parsed.source().isBlank()) {
            showUsage();
            return;
        }

        var target = resolveTargetDir(parsed.isLocal());
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Installing plugin from: " + parsed.source() + " (" + target.scopeName() + ")..." + AnsiTheme.RESET + "\n");

        try {
            Path pluginPath = resolveSource(parsed.source());
            if (pluginPath == null || !Files.isDirectory(pluginPath)) {
                ui.showError("Plugin source not found: " + parsed.source());
                return;
            }

            var manifest = discovery.loadPluginManifest(pluginPath);
            if (manifest == null) {
                ui.showError("Invalid plugin: plugin.json not found or invalid");
                return;
            }

            installToTarget(pluginPath, manifest, target.dir(), parsed.isLocal());
        } catch (Exception e) {
            ui.showError("Installation failed: " + e.getMessage());
        }
    }

    /**
     * Uninstall a plugin by name.
     */
    public void uninstall(String name) {
        if (name == null || name.isBlank()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET + " Please specify a plugin name.\n");
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Example: /plugins uninstall my-plugin\n\n" + AnsiTheme.RESET);
            return;
        }

        var pluginDir = USER_PLUGINS_DIR.resolve(name);
        if (!Files.exists(pluginDir)) {
            ui.showError("Plugin not found: " + name);
            return;
        }

        try {
            deleteDirectory(pluginDir);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Uninstalled " + name + "\n\n");
        } catch (IOException e) {
            ui.showError("Failed to uninstall: " + e.getMessage());
        }
    }

    private void showUsage() {
        ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET + " Please specify a plugin source.\n");
        ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Example: /plugins install git:https://github.com/org/plugin --global\n\n" + AnsiTheme.RESET);
    }

    private InstallArgs parseArgs(String args) {
        if (args.contains("--local")) {
            return new InstallArgs(args.replace("--local", "").trim(), true);
        } else if (args.contains("--global")) {
            return new InstallArgs(args.replace("--global", "").trim(), false);
        }
        return new InstallArgs(args, false);
    }

    private TargetDir resolveTargetDir(boolean isLocal) {
        if (isLocal) {
            return new TargetDir(LOCAL_PLUGINS_DIR, "local (project)");
        }
        return new TargetDir(USER_PLUGINS_DIR, "global (user)");
    }

    private void installToTarget(Path pluginPath, PluginDiscovery.PluginManifest manifest, Path targetPluginsDir, boolean isLocal) throws IOException {
        var destDir = targetPluginsDir.resolve(manifest.name());
        if (Files.exists(destDir)) {
            ui.printStreamingChunk("  " + AnsiTheme.WARNING + "Plugin already exists. Overwriting...\n" + AnsiTheme.RESET);
            deleteDirectory(destDir);
        }

        Files.createDirectories(destDir);
        copyDirectory(pluginPath, destDir);

        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Plugin enabled by default.\n");

        String displayPath = isLocal ? ".core-ai/plugins/" + manifest.name() : destDir.toString();
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                + " Installed " + manifest.name() + " v" + manifest.version() + "\n");
        ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Location: " + displayPath + "\n\n");
    }

    private Path resolveSource(String source) throws IOException {
        if (source.startsWith("./") || source.startsWith("../") || source.startsWith("/")) {
            var path = Path.of(source);
            if (!path.isAbsolute()) {
                path = Path.of(System.getProperty("user.dir")).resolve(path);
            }
            return path;
        } else if (source.startsWith("git:") || source.startsWith("github:") || source.startsWith("https://") || source.startsWith("git@")) {
            return cloneGitRepo(source);
        } else if (source.startsWith("npm:")) {
            return installNpmPackage(source.substring(4));
        } else {
            return Path.of(source);
        }
    }

    private Path cloneGitRepo(String source) throws IOException {
        ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Cloning git repository...\n" + AnsiTheme.RESET);

        var tempDir = Files.createTempDirectory("core-ai-plugin-");
        var url = source;
        if (url.startsWith("git:")) url = url.substring(4);
        if (url.startsWith("github:")) url = url.substring(7);

        var process = new ProcessBuilder()
            .command("git", "clone", "--depth", "1", url, tempDir.toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("git clone failed: " + error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git clone interrupted", e);
        }

        return tempDir;
    }

    private Path installNpmPackage(String packageSpec) throws IOException {
        ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Installing npm package...\n" + AnsiTheme.RESET);

        var tempDir = Files.createTempDirectory("core-ai-plugin-");

        var process = new ProcessBuilder()
            .command("npm", "install", "--prefix", tempDir.toString(), packageSpec)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("npm install failed: " + error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("npm install interrupted", e);
        }

        int slashIdx = packageSpec.lastIndexOf('/');
        return tempDir.resolve("node_modules").resolve(slashIdx >= 0 ? packageSpec.substring(slashIdx + 1) : packageSpec);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(sourcePath -> copyPath(sourcePath, source, target));
    }

    private void copyPath(Path sourcePath, Path sourceRoot, Path targetRoot) {
        try {
            var targetPath = targetRoot.resolve(sourceRoot.relativize(sourcePath));
            if (Files.isDirectory(sourcePath)) {
                Files.createDirectories(targetPath);
            } else {
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(this::deletePathQuietly);
        }
    }

    private void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // ignore deletion errors
            LOGGER.debug("Failed to delete path {}: {}", path, e.getMessage());
        }
    }

    public record InstallArgs(String source, boolean isLocal) { }
    public record TargetDir(Path dir, String scopeName) { }
}
