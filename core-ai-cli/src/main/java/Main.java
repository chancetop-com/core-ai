import ai.core.cli.CliApp;
import ai.core.cli.CliAppOptions;
import ai.core.cli.DebugLog;
import ai.core.cli.upgrade.UpgradeChecker;
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

    @Option(names = "--upgrade", description = "Check for new CLI version and show upgrade instructions")
    boolean upgrade;

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

    private static void checkUpgrade() {
        var info = new UpgradeChecker().check();
        if (info == null || info.latestVersion() == null) {
            System.out.println("Failed to check for updates. Please try again later.");
            return;
        }
        if (!info.isNewer()) {
            System.out.println("You are up to date (v" + info.currentVersion() + ")");
            return;
        }
        System.out.println("New version available: v" + info.latestVersion() + " (current: v" + info.currentVersion() + ")");
        if (info.releaseUrl() != null) {
            System.out.println("Download: " + info.releaseUrl());
        }
        System.out.println("To upgrade: download the binary for your platform from the release page above.");
    }
}
