package ai.core.cli;

import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.agent.AgentSessionRunner;
import ai.core.cli.config.InteractiveConfigSetup;
import ai.core.cli.ui.TerminalUI;

import java.nio.file.Path;

/**
 * @author stephen
 */
public class CliApp {
    private static final Path DEFAULT_CONFIG = Path.of(System.getProperty("user.home"), ".core-ai-cli", "agent.properties");

    private final Path configFile;
    private final String modelOverride;

    public CliApp(Path configFile, String modelOverride) {
        this.configFile = configFile != null ? configFile : DEFAULT_CONFIG;
        this.modelOverride = modelOverride;
    }

    public void start() {
        InteractiveConfigSetup.setupIfNeeded();
        DebugLog.log("loading config from " + configFile);
        var props = PropertiesFileSource.fromFile(configFile);
        var bootstrap = new AgentBootstrap(props);
        var result = bootstrap.initialize();
        DebugLog.log("bootstrap initialized");

        var ui = new TerminalUI();
        var runner = new AgentSessionRunner(ui, result.llmProviders, modelOverride);

        try {
            runner.run();
        } finally {
            closeQuietly(ui);
            closeShutdownResources(result);
        }
    }

    private void closeQuietly(TerminalUI ui) {
        try {
            ui.close();
        } catch (Exception ignored) {
            // terminal cleanup failure is non-critical
        }
    }

    private void closeShutdownResources(BootstrapResult result) {
        for (var resource : result.shutdownResources()) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // shutdown cleanup failure is non-critical
            }
        }
    }
}
