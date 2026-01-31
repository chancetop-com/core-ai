package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
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
    public static final String TOOL_NAME = "run_python_script";

    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final long ASYNC_TIMEOUT_SECONDS = 600;
    private static final Logger LOGGER = LoggerFactory.getLogger(PythonScriptTool.class);
    private static final String TOOL_DESC = """
            Executes a Python script and returns its output.


            Usage:

            - Provide Python code via 'code' parameter OR a script file path via 'script_path' parameter.

            - If both 'code' and 'script_path' are provided, 'code' takes precedence.

            - The script will be executed with a timeout of 60 seconds by default.

            - Set 'async' to true for long-running scripts. Use 'async_task_output' tool to check progress.

            - The output of the script (stdout and stderr) will be returned as a string.

            - If the script times out or exits with a non-zero code, an error message will be returned.
            """;

    public static Builder builder() {
        return new Builder();
    }

    public static String exec(String code, long timeout) {
        return exec(code, null, timeout);
    }

    public static String exec(String code, Path scriptPath, long timeout) {
        Path tmp = null;
        boolean shouldDeleteTmp = false;

        try {
            var scriptInfo = prepareScript(code, scriptPath);
            if (scriptInfo.error != null) {
                return scriptInfo.error;
            }
            tmp = scriptInfo.tempFile;
            shouldDeleteTmp = scriptInfo.shouldDeleteTemp;

            return runPythonProcess(scriptInfo.executeScript, timeout);
        } catch (Exception e) {
            var error = "Python script execution failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        } finally {
            if (shouldDeleteTmp && tmp != null) {
                deleteTempFile(tmp);
            }
        }
    }

    private static ScriptInfo prepareScript(String code, Path scriptPath) throws IOException {
        if (code != null && !code.isBlank()) {
            var tmp = core.framework.util.Files.tempFile();
            Files.writeString(tmp, code);
            LOGGER.info("Python script written to temp file: {}", tmp.toAbsolutePath());
            return new ScriptInfo(tmp, tmp, true, null);
        } else if (scriptPath != null) {
            if (!Files.exists(scriptPath)) {
                return new ScriptInfo(null, null, false, "Error: Script file does not exist: " + scriptPath);
            }
            if (!Files.isReadable(scriptPath)) {
                return new ScriptInfo(null, null, false, "Error: Script file is not readable: " + scriptPath);
            }
            LOGGER.info("Executing Python script from path: {}", scriptPath.toAbsolutePath());
            return new ScriptInfo(null, scriptPath, false, null);
        } else {
            return new ScriptInfo(null, null, false, "Error: Either 'code' or 'script_path' parameter is required");
        }
    }

    private static String runPythonProcess(Path executeScript, long timeout) throws IOException {
        var dir = executeScript.getParent() != null
                ? executeScript.getParent().toAbsolutePath().toString()
                : core.framework.util.Files.tempDir().toAbsolutePath().toString();

        var command = new ArrayList<>(Arrays.asList("python", executeScript.toAbsolutePath().toString()));
        var pb = new ProcessBuilder(command);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.directory(new File(dir));
        pb.redirectErrorStream(true);

        LOGGER.info("Executing Python script with timeout {} seconds", timeout);
        var process = pb.start();

        return handleProcessResult(process, timeout);
    }

    private static String handleProcessResult(Process process, long timeout) {
        var timedOut = waitFor(process, timeout);

        if (timedOut) {
            process.destroyForcibly();
            waitFor(process);
            var errorLines = readProcessOutput(process);
            var error = "Python script timed out after " + timeout + " seconds\nPlease check your script\n" + String.join("\n", errorLines);
            LOGGER.warn(error);
            return error;
        }

        var outputLines = readProcessOutput(process);
        var exitCode = process.exitValue();

        if (exitCode != 0) {
            var error = "Python script exited with code " + exitCode + ":\n" + String.join("\n", outputLines);
            LOGGER.warn(error);
            return error;
        }

        LOGGER.info("Python script executed successfully, output: {} lines", outputLines.size());
        return outputLines.isEmpty() ? "" : String.join("\n", outputLines);
    }

    private static java.util.List<String> readProcessOutput(Process process) {
        try {
            return InputStreamUtil.readStream(process.getInputStream());
        } catch (IOException e) {
            LOGGER.error("Failed to read process output: {}", e.getMessage());
            return java.util.List.of("Error reading output: " + e.getMessage());
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
    public ToolCallResult execute(String text, ExecutionContext context) {
        return doExecute(text);
    }

    @Override
    public ToolCallResult execute(String text) {
        return doExecute(text);
    }

    private ToolCallResult doExecute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var code = (String) argsMap.get("code");
            var scriptPathStr = (String) argsMap.get("script_path");
            var asyncMode = Boolean.TRUE.equals(argsMap.get("async"));

            if (Strings.isBlank(code) && Strings.isBlank(scriptPathStr)) {
                return ToolCallResult.failed("Error: Either 'code' or 'script_path' parameter is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            Path scriptPath = scriptPathStr != null ? Path.of(scriptPathStr) : null;

            if (asyncMode) {
                return executeAsync(code, scriptPath, startTime);
            }

            var result = exec(code, scriptPath, DEFAULT_TIMEOUT_SECONDS);
            return ToolCallResult.completed(result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("codeLength", code != null ? code.length() : 0)
                    .withStats("scriptPath", scriptPathStr);
        } catch (Exception e) {
            var error = "Failed to parse Python script arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult executeAsync(String code, Path scriptPath, long startTime) {
        var asyncExecutor = AsyncToolTaskExecutor.getInstance();
        var taskId = asyncExecutor.submit("py", TOOL_NAME, () -> exec(code, scriptPath, ASYNC_TIMEOUT_SECONDS));

        var message = scriptPath != null
                ? "Executing script: " + scriptPath
                : "Executing Python code (" + (code != null ? code.length() : 0) + " chars)";

        return ToolCallResult.pending(taskId, message)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("async", true);
    }

    @Override
    public ToolCallResult poll(String taskId) {
        return AsyncToolTaskExecutor.getInstance().poll(taskId);
    }

    @Override
    public ToolCallResult cancel(String taskId) {
        return AsyncToolTaskExecutor.getInstance().cancel(taskId);
    }

    private record ScriptInfo(Path tempFile, Path executeScript, boolean shouldDeleteTemp, String error) {
    }

    public static class Builder extends ToolCall.Builder<Builder, PythonScriptTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public PythonScriptTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "code", "Python code to execute. Either 'code' or 'script_path' is required."),
                    ToolCallParameters.ParamSpec.of(String.class, "script_path", "Path to a Python script file to execute. Either 'code' or 'script_path' is required."),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "async", "Set to true to run the script asynchronously. Use 'async_task_output' tool to check progress.")
            ));
            var tool = new PythonScriptTool();
            build(tool);
            return tool;
        }
    }
}
