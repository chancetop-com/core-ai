package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.utils.InputStreamUtil;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author stephen
 */
public class PythonScriptTool extends ToolCall {
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final Logger LOGGER = LoggerFactory.getLogger(PythonScriptTool.class);
    public static Builder builder() {
        return new Builder();
    }

    public static String exec(String code, long timeout) {
        var dir = core.framework.util.Files.tempDir().toAbsolutePath().toString();
        var tmp = core.framework.util.Files.tempFile();

        try {
            // Write Python code to temporary file
            Files.writeString(tmp, code);
            LOGGER.info("Python script written to temp file: {}", tmp.toAbsolutePath());

            var command = new ArrayList<>(Arrays.asList("python", tmp.toAbsolutePath().toString()));
            var pb = new ProcessBuilder(command);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.directory(new File(dir));
            pb.redirectErrorStream(true);  // Merge stdout and stderr to avoid blocking

            LOGGER.info("Executing Python script with timeout {} seconds", timeout);
            var process = pb.start();

            var timedOut = waitFor(process, timeout);

            if (timedOut) {
                process.destroyForcibly();
                waitFor(process);
                var errorLines = InputStreamUtil.readStream(process.getInputStream());
                var error = "Python script timed out after " + timeout + " seconds\nPlease check your script\n" + String.join("\n", errorLines);
                LOGGER.warn(error);
                return error;
            }

            var outputLines = InputStreamUtil.readStream(process.getInputStream());
            var exitCode = process.exitValue();

            if (exitCode != 0) {
                var error = "Python script exited with code " + exitCode + ":\n" + String.join("\n", outputLines);
                LOGGER.warn(error);
                return error;
            }

            LOGGER.info("Python script executed successfully, output: {} lines", outputLines.size());
            if (!outputLines.isEmpty()) {
                LOGGER.debug("Script output:\n{}", String.join("\n", outputLines));
            }

            // Return all output lines, not just the last one
            return outputLines.isEmpty() ? "" : String.join("\n", outputLines);

        } catch (Exception e) {
            var error = "Python script execution failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        } finally {
            deleteTempFile(tmp);
        }
    }

    private static void deleteTempFile(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete temp file: {}, {}", tmp, e.getMessage());
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
            var code = (String) argsMap.get("code");

            if (Strings.isBlank(code)) {
                return "Error: code parameter is required";
            }

            return exec(code, DEFAULT_TIMEOUT_SECONDS);
        } catch (Exception e) {
            var error = "Failed to parse Python script arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, PythonScriptTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public PythonScriptTool build() {
            this.parameters(ToolCallParameters.of(String.class, "code", "python code"));
            var tool = new PythonScriptTool();
            build(tool);
            return tool;
        }
    }
}
