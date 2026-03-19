package ai.core.cli.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ScriptHookRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptHookRunner.class);
    private static final long TIMEOUT_SECONDS = 10;

    private final Path workspace;

    public ScriptHookRunner(Path workspace) {
        this.workspace = workspace;
    }

    public String run(String command, Map<String, String> env) {
        try {
            var pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(false);
            pb.environment().putAll(env);
            var process = pb.start();

            String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("Hook script timed out: {}", command);
                return "";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                LOGGER.warn("Hook script exited with code {}: {}", exitCode, command);
                return "";
            }

            return stdout.strip();
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Hook script failed: {} - {}", command, e.getMessage());
            return "";
        }
    }
}
