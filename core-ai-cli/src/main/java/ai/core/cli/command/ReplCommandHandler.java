package ai.core.cli.command;

import ai.core.cli.DebugLog;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.upgrade.UpgradeChecker;
import ai.core.cli.upgrade.UpgradeDownloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
public class ReplCommandHandler {
    private final TerminalUI ui;
    private final UpgradeChecker upgradeChecker;

    public ReplCommandHandler(TerminalUI ui) {
        this.ui = ui;
        this.upgradeChecker = new UpgradeChecker();
    }

    public void handle(String input) {
        var trimmed = input.trim();
        if (!trimmed.startsWith("/")) return;

        if ("/".equals(trimmed)) {
            printHelp();
            return;
        }

        var parts = trimmed.split("\\s+", 2);
        var command = parts[0];

        switch (command) {
            case "/help" -> printHelp();
            case "/init" -> initProject();
            case "/debug" -> toggleDebug();
            case "/upgrade" -> checkUpgrade();
            case "/exit" -> {
            }
            default -> ui.printStreamingChunk(AnsiTheme.WARNING + "Unknown command: " + command + ". Type /help for available commands." + AnsiTheme.RESET + "\n");
        }
    }

    private void checkUpgrade() {
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Checking for updates..." + AnsiTheme.RESET + "\n");
        var info = upgradeChecker.check();
        if (info == null || info.latestVersion() == null) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  Failed to check for updates. Please try again later.\n" + AnsiTheme.RESET);
            return;
        }
        if (!info.isNewer()) {
            ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " You are up to date (v" + info.currentVersion() + ")\n");
            return;
        }

        String platform = UpgradeDownloader.detectPlatformSuffix();
        ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "New version available!" + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("  Current: v" + info.currentVersion() + "  →  Latest: " + AnsiTheme.SUCCESS + "v" + info.latestVersion() + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk("  Platform: " + AnsiTheme.MUTED + platform + AnsiTheme.RESET + "\n\n");

        Path installDir = UpgradeDownloader.resolveInstallDir();
        try {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Downloading core-ai-cli-" + platform + "..." + AnsiTheme.RESET + "\n");
            Path downloaded = UpgradeDownloader.download(info.latestVersion(), installDir);

            Path currentBinary = UpgradeDownloader.findCurrentBinary();
            if (currentBinary != null) {
                ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Replacing " + currentBinary.getFileName() + "..." + AnsiTheme.RESET + "\n");
                Path replaced = UpgradeDownloader.tryReplaceCurrent(downloaded, currentBinary);
                if (replaced.equals(currentBinary)) {
                    ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Replaced. Restart to use v" + info.latestVersion() + "\n");
                } else {
                    ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Saved as " + replaced.getFileName() + "\n");
                    ui.printStreamingChunk("  " + AnsiTheme.MUTED + "  Replace manually and restart to use v" + info.latestVersion() + AnsiTheme.RESET + "\n");
                }
            } else {
                ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Downloaded to " + downloaded + "\n");
                ui.printStreamingChunk("  " + AnsiTheme.MUTED + "  Run: " + downloaded + AnsiTheme.RESET + "\n");
            }

            if (!UpgradeDownloader.isInPath(installDir)) {
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Install directory is not in PATH." + AnsiTheme.RESET + "\n");
                ui.printStreamingChunk(UpgradeDownloader.pathSetupInstructions(installDir) + "\n");
            }
            ui.printStreamingChunk("\n");
        } catch (UpgradeDownloader.UpgradeException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void printHelp() {
        var sb = new StringBuilder("Available commands:\n");
        for (SlashCommand cmd : SlashCommandRegistry.all()) {
            sb.append(String.format("  %s%-16s %s%s%n",
                    AnsiTheme.CMD_NAME, cmd.name(),
                    AnsiTheme.CMD_DESC + cmd.description(), AnsiTheme.RESET));
        }
        ui.printStreamingChunk(sb.toString());
    }

    private void toggleDebug() {
        if (DebugLog.isEnabled()) {
            DebugLog.disable();
            System.clearProperty("core.ai.debug");
            ui.printStreamingChunk("Debug mode: OFF\n");
        } else {
            DebugLog.enable();
            System.setProperty("core.ai.debug", "true");
            ui.printStreamingChunk("Debug mode: ON\n");
        }
    }

    private void initProject() {
        Path dir = Path.of(".core-ai");
        Path file = dir.resolve("instructions.md");
        if (Files.exists(file)) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  .core-ai/instructions.md already exists.\n" + AnsiTheme.RESET);
            return;
        }
        String template = """
                # Project Instructions

                ## Guidelines
                - Code comments in English
                - Prefer self-descriptive code over comments

                ## Project Structure
                <!-- Describe your project structure here -->

                ## Conventions
                <!-- Add project-specific conventions -->
                """;
        try {
            Files.createDirectories(dir);
            Files.writeString(file, template);
            MemoryTriggerService.getInstance().ensureDirectories();
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Created .core-ai/instructions.md — edit it to customize agent behavior.\n\n");
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to create: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }
}
