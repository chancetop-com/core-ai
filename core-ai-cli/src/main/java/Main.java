import ai.core.cli.CliApp;
import ai.core.cli.CliAppOptions;
import ai.core.cli.DebugLog;
import ai.core.cli.upgrade.UpgradeChecker;
import ai.core.cli.upgrade.UpgradeDownloader;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * @author stephen
 */
@Command(name = "core-ai-cli", versionProvider = VersionProvider.class, description = "Core-AI CLI agent")
public class Main implements Callable<Integer> {
    public static void main(String[] args) {
        initSlf4j();
        System.exit(new CommandLine(new Main()).execute(args));
    }

    private static void initSlf4j() {
        System.setProperty("slf4j.provider", "ai.core.cli.log.CliLoggerServiceProvider");
        var stderr = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream(), false, stderr.charset()));
        LoggerFactory.getILoggerFactory();
        System.setErr(stderr);
    }

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show help")
    boolean helpRequested;

    @Option(names = {"-V", "--version"}, versionHelp = true, description = "Show version")
    boolean versionRequested;

    @Option(names = "--debug", description = "Enable debug output")
    boolean debug;

    @Option(names = "--model", description = "Override LLM model name")
    String model;

    @Option(names = "--prompt", description = "Send one prompt, print the response, and exit")
    String prompt;

    @Option(names = "--config", description = "Config file path")
    Path configFile;

    @Option(names = "--dangerously-skip-permissions", description = "Skip all tool approval prompts")
    boolean skipPermissions;

    @Option(names = {"-c", "--continue"}, description = "Resume the most recent session")
    boolean continueSession;

    @Option(names = "--resume", description = "Pick a recent session to resume")
    boolean resume;

    @Option(names = "--workspace", description = "Set the working directory for the agent session")
    Path workspace;

    @Option(names = "--server", description = "Connect to a remote A2A agent or core-ai-server (e.g. http://localhost:8080)")
    String serverUrl;

    @Option(names = "--api-key", description = "Bearer token for remote server authentication")
    String apiKey;

    @Option(names = "--agent-id", description = "Agent ID or A2A tenant to use on remote server (default: default-assistant)")
    String agentId;

    @Option(names = "--serve", description = "Start A2A web server mode")
    boolean serve;

    @Option(names = "--port", description = "A2A server port (default: 9527)", defaultValue = "9527")
    int port;

    @Option(names = "--headless", description = "A2A server without opening browser")
    boolean headless;

    @Option(names = "--web-dir", description = "Serve frontend from local directory (for dev)")
    Path webDir;

    @Option(names = "--acp-agent", description = "Start in ACP (Agent Client Protocol) stdio mode for editor integration")
    boolean acpAgent;

    @Option(names = "--upgrade", description = "Download and install the latest CLI version")
    boolean upgrade;

    @Option(names = "--upgrade-dir", description = "Install directory for --upgrade (default: current binary dir or ~/.core-ai/bin/)")
    Path upgradeDir;

    @Override
    public Integer call() {
        if (upgrade) {
            checkUpgrade();
            return 0;
        }
        if (debug) {
            DebugLog.enable();
            System.setProperty("core.ai.debug", "true");
        }
        var options = new CliAppOptions(configFile, model, prompt, skipPermissions, continueSession, resume, workspace);
        if (acpAgent) {
            new CliApp(options).startAcpAgent();
        } else if (serve) {
            new CliApp(options).startServe(port, !headless, webDir);
        } else if (serverUrl != null) {
            new CliApp(options).startRemote(serverUrl, apiKey, agentId);
        } else {
            new CliApp(options).start();
        }
        return 0;
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private void checkUpgrade() {
        var checker = new UpgradeChecker();
        var info = checker.check();
        if (info == null || info.latestVersion() == null) {
            System.out.println("Failed to check for updates. Please try again later.");
            System.exit(1);
            return;
        }
        if (!info.isNewer()) {
            System.out.println("You are up to date (v" + info.currentVersion() + ")");
            return;
        }

        System.out.println("New version available: v" + info.latestVersion() + " (current: v" + info.currentVersion() + ")");
        System.out.println("Platform: " + UpgradeDownloader.detectPlatformSuffix());

        Path installDir = upgradeDir != null ? upgradeDir : UpgradeDownloader.resolveInstallDir();
        try {
            System.out.println("Downloading to " + installDir + "...");
            Path downloaded = UpgradeDownloader.download(info.latestVersion(), installDir);

            Path currentBinary = UpgradeDownloader.findCurrentBinary();
            if (currentBinary != null) {
                Path replaced = UpgradeDownloader.tryReplaceCurrent(downloaded, currentBinary);
                if (replaced.equals(currentBinary)) {
                    if (UpgradeDownloader.isUpgradeScheduled(currentBinary)) {
                        System.out.println("Replacement scheduled — binary will be updated automatically in a moment.");
                        System.out.println("Restart core-ai-cli to use v" + info.latestVersion());
                    } else {
                        System.out.println("Replaced " + currentBinary.getFileName() + ". Restart to use v" + info.latestVersion());
                    }
                } else {
                    System.out.println("Saved as " + replaced + " (cannot overwrite running binary)");
                    System.out.println("To complete upgrade: replace " + currentBinary + " with " + replaced + ", then restart.");
                }
            } else {
                System.out.println("Downloaded to " + downloaded);
                System.out.println("Run: " + downloaded);
            }

            if (!UpgradeDownloader.isInPath(installDir)) {
                System.out.println();
                System.out.println("Install directory is not in PATH:");
                System.out.println(UpgradeDownloader.pathSetupInstructions(installDir));
            }
        } catch (UpgradeDownloader.UpgradeException e) {
            System.err.println("Upgrade failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
