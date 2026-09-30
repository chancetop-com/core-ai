package ai.core.cli.agent;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.upgrade.UpgradeChecker;
import ai.core.cli.upgrade.UpgradeDownloader;
import ai.core.cli.upgrade.VersionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

class SessionUpgradeHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionUpgradeHandler.class);
    private static final AtomicBoolean UPGRADE_CHECK_DONE = new AtomicBoolean(false);

    private final TerminalUI ui;
    private final UpgradeChecker upgradeChecker = new UpgradeChecker();

    SessionUpgradeHandler(TerminalUI ui) {
        this.ui = ui;
    }

    void checkAndHintUpgrade() {
        if (!UPGRADE_CHECK_DONE.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try {
                Path currentBinary = UpgradeDownloader.findCurrentBinary();
                UpgradeDownloader.cleanupReplacedBinaries(currentBinary);
                if (UpgradeDownloader.isUpgradeScheduled(currentBinary)) {
                    UpgradeDownloader.retryScheduledUpgrade(currentBinary);
                    ui.getWriter().println("  " + AnsiTheme.MUTED + "Applying the pending update — restart core-ai-cli to use the new version." + AnsiTheme.RESET);
                    ui.getWriter().flush();
                    return;
                }
                var info = upgradeChecker.check();
                if (info.isNewer()) {
                    ui.getWriter().println("  " + AnsiTheme.WARNING + "New version v" + info.latestVersion()
                            + " available! Type /upgrade to install." + AnsiTheme.RESET);
                    ui.getWriter().flush();
                }
            } catch (Exception e) {
                LOGGER.debug("Upgrade check failed: {}", e.getMessage());
            }
        });
    }

    boolean handleUpgrade() {
        var info = upgradeChecker.check();
        if (!info.isNewer()) {
            ui.getWriter().println("  " + AnsiTheme.MUTED + "You are up to date (v" + VersionUtil.getCurrentVersion() + ")" + AnsiTheme.RESET);
            ui.getWriter().flush();
            return false;
        }
        ui.getWriter().println();
        ui.getWriter().println("  " + AnsiTheme.CMD_NAME + "New version available!" + AnsiTheme.RESET);
        ui.getWriter().println("  Current: v" + info.currentVersion() + "  →  Latest: v" + info.latestVersion());
        ui.getWriter().println();
        ui.getWriter().println("  " + AnsiTheme.WARNING + "This will exit the CLI and replace the current binary." + AnsiTheme.RESET);
        ui.getWriter().flush();
        String answer = ui.readInput("  Proceed with upgrade? (y/N): ");
        if (answer == null || !"y".equalsIgnoreCase(answer.trim())) {
            ui.getWriter().println("  " + AnsiTheme.MUTED + "Cancelled." + AnsiTheme.RESET);
            ui.getWriter().flush();
            return false;
        }
        return performUpgradeInstall(info);
    }

    private boolean performUpgradeInstall(UpgradeChecker.UpgradeInfo info) {
        try {
            Path currentBinary = UpgradeDownloader.findCurrentBinary();
            if (currentBinary == null) {
                ui.getWriter().println("  " + AnsiTheme.ERROR + "Cannot locate current binary." + AnsiTheme.RESET);
                ui.getWriter().flush();
                return false;
            }
            Path installDir = UpgradeDownloader.resolveInstallDir();
            ui.getWriter().println("  " + AnsiTheme.MUTED + "Downloading " + UpgradeDownloader.archiveFileName() + "..." + AnsiTheme.RESET);
            ui.getWriter().flush();
            Path downloaded = UpgradeDownloader.download(info.latestVersion(), installDir);
            ui.getWriter().print("  Replacing " + currentBinary.getFileName() + "...");
            ui.getWriter().flush();
            Path replaced = UpgradeDownloader.tryReplaceCurrent(downloaded, currentBinary);
            if (UpgradeDownloader.isUpgradeScheduled(currentBinary)) {
                ui.getWriter().println(" scheduled");
                ui.getWriter().println();
                ui.getWriter().println("  " + AnsiTheme.SUCCESS + "Update scheduled. CLI will exit now." + AnsiTheme.RESET);
                ui.getWriter().println("  " + AnsiTheme.MUTED + "It completes automatically — close all core-ai-cli windows and start it again to use v"
                        + info.latestVersion() + "." + AnsiTheme.RESET);
                ui.getWriter().flush();
                return true;
            }
            if (replaced.equals(currentBinary)) {
                ui.getWriter().println(" done");
                ui.getWriter().println("  " + AnsiTheme.SUCCESS + "Upgrade complete. Restart to use v" + info.latestVersion() + "." + AnsiTheme.RESET);
                ui.getWriter().flush();
            } else {
                ui.getWriter().println(" " + AnsiTheme.MUTED + "(cannot replace the running binary)" + AnsiTheme.RESET);
                ui.getWriter().println("  Saved as " + replaced);
                ui.getWriter().println("  " + AnsiTheme.MUTED + "The update completes automatically the next time you start core-ai-cli." + AnsiTheme.RESET);
                ui.getWriter().flush();
            }
        } catch (Exception e) {
            LOGGER.error("Upgrade failed", e);
            ui.getWriter().println("  " + AnsiTheme.ERROR + "Upgrade failed: " + e.getMessage() + AnsiTheme.RESET);
            ui.getWriter().flush();
        }
        return false;
    }
}
