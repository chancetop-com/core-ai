import ai.core.cli.CliApp;
import ai.core.cli.DebugLog;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * @author stephen
 */
@Command(name = "core-ai-cli", version = "1.0.0", description = "Core-AI CLI agent")
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

    @Option(names = "--server", description = "Connect to remote core-ai-server (e.g. http://localhost:8080)")
    String serverUrl;

    @Option(names = "--api-key", description = "API key for remote server authentication")
    String apiKey;

    @Option(names = "--agent-id", description = "Agent ID to use on remote server (default: default-assistant)")
    String agentId;

    @Override
    public Integer call() {
        if (debug) {
            DebugLog.enable();
            System.setProperty("core.ai.debug", "true");
        }
        if (serverUrl != null) {
            new CliApp(configFile, model, skipPermissions, continueSession, resume, workspace)
                    .startRemote(serverUrl, apiKey, agentId);
        } else {
            new CliApp(configFile, model, skipPermissions, continueSession, resume, workspace).start();
        }
        return 0;
    }
}
