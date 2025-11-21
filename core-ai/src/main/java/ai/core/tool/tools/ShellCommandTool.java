package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.utils.InputStreamUtil;
import ai.core.utils.ShellUtil;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class ShellCommandTool extends ToolCall {
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final Logger LOGGER = LoggerFactory.getLogger(ShellCommandTool.class);
    public static Builder builder() {
        return new Builder();
    }

    public static String exec(List<String> commands, String workdir, long timeout) {
        var dir = workdir;
        if (Strings.isBlank(dir)) dir = core.framework.util.Files.tempDir().toAbsolutePath().toString();

        // Validate working directory
        var workDir = new File(dir);
        if (!workDir.exists()) {
            String error = "Error: workspace directory does not exist: " + dir;
            LOGGER.warn(error);
            return error;
        }
        if (!workDir.isDirectory()) {
            String error = "Error: workspace path is not a directory: " + dir;
            LOGGER.warn(error);
            return error;
        }

        var pb = new ProcessBuilder(commands);
        pb.directory(workDir);
        pb.redirectErrorStream(true);  // Merge stdout and stderr to avoid blocking

        try {
            LOGGER.info("Executing shell command in directory {}: {}", dir, String.join(" ", commands));
            var process = pb.start();

            var timedOut = waitFor(process, timeout);

            if (timedOut) {
                process.destroyForcibly();
                waitFor(process);
                var errorLines = InputStreamUtil.readStream(process.getInputStream());
                var error = "Command timed out after " + timeout + " seconds\nPlease check your command and workspace dir\n" + String.join("\n", errorLines);
                LOGGER.warn(error);
                return error;
            }

            var outputLines = InputStreamUtil.readStream(process.getInputStream());
            var exitCode = process.exitValue();

            if (exitCode != 0) {
                String error = "Command exited with code " + exitCode + ":\n" + String.join("\n", outputLines);
                LOGGER.warn(error);
                return error;
            }

            LOGGER.info("Shell command executed successfully, output: {} lines", outputLines.size());
            if (!outputLines.isEmpty()) {
                LOGGER.debug("Command output:\n{}", String.join("\n", outputLines));
            }
            return outputLines.isEmpty() ? "" : String.join("\n", outputLines);
        } catch (Exception e) {
            var error = "Command execution failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private static void waitFor(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitFor(Process process, long timeout) {
        boolean timedOut;
        try {
            timedOut = !process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
        }
        return timedOut;
    }

    @Override
    public String call(String text) {
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var workspaceDir = (String) argsMap.get("workspace_dir");
            var command = (String) argsMap.get("command");

            if (Strings.isBlank(command)) {
                return "Error: command parameter is required";
            }

            // Build command list properly to handle arguments with spaces
            var shellPrefix = ShellUtil.getPreferredShellCommandPrefix(ShellUtil.getSystemType()).trim();
            var prefixParts = shellPrefix.split(" ");
            var commands = new ArrayList<>(Arrays.asList(prefixParts));
            commands.add(command);  // Add command as a single argument

            return exec(commands, workspaceDir, DEFAULT_TIMEOUT_SECONDS);
        } catch (Exception e) {
            var error = "Failed to parse shell command arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, ShellCommandTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public ShellCommandTool build() {
            this.parameters(ToolCallParameters.of(String.class, "workspace_dir", "dir of command to exec", String.class, "command", "command string"));
            var tool = new ShellCommandTool();
            build(tool);
            return tool;
        }
    }
}