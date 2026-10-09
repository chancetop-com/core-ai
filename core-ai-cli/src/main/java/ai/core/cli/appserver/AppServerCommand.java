package ai.core.cli.appserver;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code core-ai-cli app-server}: stdio JSON-RPC engine for GUI clients (core-ai-desktop). stdout
 * carries protocol frames only — early stdout is redirected to stderr before the engine boots.
 *
 * @author stephen
 */
@Command(name = "app-server", description = "Start in app-server mode (stdio JSON-RPC for GUI clients)")
public class AppServerCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Set the working directory for the engine sessions")
    Path workspace;

    @Option(names = "--config", description = "Config file path")
    Path configFile;

    @Override
    public Integer call() {
        System.setProperty("core.appName", "core-ai-cli");
        var protocolOut = System.out;
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        var resolvedWorkspace = workspace != null ? workspace : Path.of("").toAbsolutePath();
        var engine = new AppServerEngine(resolvedWorkspace, configFile);
        var server = new AppServer(System.in, protocolOut, engine);
        return server.run();
    }
}
