package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.utils.InputStreamUtil;
import core.framework.json.JSON;
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
    private static final long DEFAULT_TIMEOUT_SECONDS = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(PythonScriptTool.class);
    public static Builder builder() {
        return new Builder();
    }

    public static String exec(String code, long timeout) {
        var dir = core.framework.util.Files.tempDir().toAbsolutePath().toString();
        var tmp = core.framework.util.Files.tempFile();
        try {
            Files.writeString(tmp, code);

            var command = new ArrayList<>(Arrays.asList("python", tmp.toAbsolutePath().toString()));
            var pb = new ProcessBuilder(command);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.directory(new File(dir));
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
            return outputLines.isEmpty() ? null : outputLines.getLast()
                    .replaceAll("'", "\"")
                    .replaceAll("\\\\", "/")
                    .replaceAll("None", "null")
                    .replaceAll("False", "false");

        } catch (Exception e) {
            return e.getMessage();
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
        var argsMap = JSON.fromJSON(Map.class, text);
        var code = (String) argsMap.get("code");
        return exec(code, DEFAULT_TIMEOUT_SECONDS);
    }

    public static class Builder extends ToolCall.Builder<Builder, PythonScriptTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public PythonScriptTool build() {
            var tool = new PythonScriptTool();
            build(tool);
            return tool;
        }
    }
}
