package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.utils.InputStreamUtil;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class ShellCommandTool extends ToolCall {
    private static final long DEFAULT_TIMEOUT_SECONDS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(ShellCommandTool.class);
    public static Builder builder() {
        return new Builder();
    }

    public static String exec(List<String> commands, String dir, long timeout) {
        var pb = new ProcessBuilder(commands);
        try {
            pb.directory(new File(Objects.requireNonNullElse(dir, ".")));
            var process = pb.start();

            boolean timedOut = waitFor(process, timeout);

            if (timedOut) {
                process.destroyForcibly();
                waitFor(process);
                var errorLines = InputStreamUtil.readStream(process.getErrorStream());
                return "Command timed out after " + timeout + " " + "\nPlease check your command and workspace dir\n" + String.join("\n", errorLines);
            }

            var outputLines = InputStreamUtil.readStream(process.getInputStream());
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                var errorLines = InputStreamUtil.readStream(process.getErrorStream());
                return String.join("\n", errorLines);
            }

            LOGGER.debug(String.join("\n", outputLines));
            return outputLines.isEmpty() ? null : String.join("\n", outputLines);
        } catch (Exception e) {
            return e.getMessage();
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
        var argsMap = JSON.fromJSON(Map.class, text);
        var workspaceDir = (String) argsMap.get("workspace_dir");
        var osName = System.getProperty("os.name").toLowerCase(Locale.getDefault());
        var command = (String) argsMap.get("command");
        List<String> commands;
        if (osName.contains("win")) {
            commands = Arrays.asList("powershell", "-Command", command);
        } else {
            commands = Arrays.stream(command.split(" ")).toList();
        }
        return exec(commands, workspaceDir, DEFAULT_TIMEOUT_SECONDS);
    }

    public static class Builder extends ToolCall.Builder<Builder, ShellCommandTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public ShellCommandTool build() {
            var tool = new ShellCommandTool();
            build(tool);
            return tool;
        }
    }
}