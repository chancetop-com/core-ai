package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.telemetry.AgentTracer;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.utils.JsonUtil;
import core.framework.json.JSON;
import core.framework.util.Strings;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class ToolExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolExecutor.class);

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    private static String prettyContent(String content) {
        try {
            var parsed = JsonUtil.OBJECT_MAPPER.readValue(content, Object.class);
            return JsonUtil.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (Exception ignored) {
            return content;
        }
    }

    private final List<AbstractLifecycle> lifecycles;
    private final AgentTracer tracer;
    private final Consumer<NodeStatus> statusUpdater;
    // Supplies the span context of the LLM call that triggered the current tool batch, scoped to the owning agent.
    // Provided by the agent so a sub-agent's tool spans nest under the sub-agent's own LLM span.
    private final Supplier<SpanContext> llmSpanContextSupplier;
    private boolean authenticated = false;

    public ToolExecutor(List<AbstractLifecycle> lifecycles, AgentTracer tracer, Consumer<NodeStatus> statusUpdater,
                        Supplier<SpanContext> llmSpanContextSupplier) {
        this.lifecycles = lifecycles;
        this.tracer = tracer;
        this.statusUpdater = statusUpdater;
        this.llmSpanContextSupplier = llmSpanContextSupplier;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public ToolCallResult execute(ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        var args = tool.parseArguments(functionCall.function.arguments);
        tool.normalizeArguments(args);
        functionCall.function.arguments = JsonUtil.toJson(args);

        lifecycles.forEach(lc -> lc.beforeTool(functionCall, context));
        var result = executeWithoutLifecycle(tool, functionCall, args, context);
        lifecycles.forEach(lc -> lc.afterTool(functionCall, context, result));
        return result;
    }

    public ToolCallResult executeWithoutLifecycle(ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        var args = tool.parseArguments(functionCall.function.arguments);
        return doExecute(tool, functionCall, args, context);
    }

    public ToolCallResult executeWithoutLifecycle(ToolCall tool, FunctionCall functionCall, Map<String, Object> args, ExecutionContext context) {
        return doExecute(tool, functionCall, args, context);
    }

    private ToolCallResult doExecute(ToolCall tool, FunctionCall functionCall, Map<String, Object> args, ExecutionContext context) {
        var sandbox = context.getSandbox();
        var useSandbox = sandbox != null && sandbox.shouldIntercept(tool.getName());

        // strip save_to_file from args before validation and tool execution
        String saveToFile = extractSaveToFile(args);
        if (saveToFile != null) {
            functionCall.function.arguments = JsonUtil.toJson(args);
        }

        var validationResult = validate(tool, args, useSandbox);
        if (validationResult != null) {
            return validationResult;
        }

        LOGGER.debug("tool {}: {}", functionCall.function.name, functionCall.function.arguments);
        var startTime = System.currentTimeMillis();

        ToolCallResult result;
        if (useSandbox) {
            LOGGER.debug("sandbox intercepting tool: {}", tool.getName());
            result = traceToolSpan(functionCall, tool.isSubAgent(), () -> sandbox.execute(tool.getName(), functionCall.function.arguments, context));
            result.withStats("executionMode", "sandbox");
            result.withStats("sandboxId", sandbox.getId());
        } else {
            result = executeWithTimeout(tool, functionCall, context);
        }

        result.withToolName(tool.getName()).withDuration(System.currentTimeMillis() - startTime);
        handleAsyncResult(result, tool, functionCall, context);

        if (saveToFile != null && result.isCompleted()) {
            result = saveResultToFile(saveToFile, result, sandbox, tool.getName());
        }

        LOGGER.debug("tool {} completed in {}ms, stats: {}", result.getToolName(), result.getDurationMs(), result.getStats());
        return result;
    }

    private String extractSaveToFile(Map<String, Object> args) {
        var value = args.remove(ToolCall.SAVE_TO_FILE_PARAM);
        if (value == null) return null;
        var saveToFile = value.toString();
        if (saveToFile.isBlank() || "auto".equals(saveToFile)) {
            return "/workspace/tool_result_" + timestamp() + ".json";
        }
        if (!saveToFile.startsWith("/")) {
            return "/workspace/" + saveToFile;
        }
        return saveToFile;
    }

    private ToolCallResult saveResultToFile(String filePath, ToolCallResult result, ai.core.sandbox.Sandbox sandbox, String toolName) {
        var content = result.getResult();
        if (content == null) return result;
        if (content.length() > ToolCall.MAX_SAVE_TO_FILE_SIZE) {
            double mb = content.length() / (1024.0 * 1024.0);
            return result.withResult(String.format(
                "save_to_file failed: result size (%.1f MB) exceeds limit (10 MB). Use pagination or filtering to reduce result size.", mb));
        }
        try {
            var prettyContent = prettyContent(content);
            writeFile(sandbox, filePath, prettyContent.getBytes(StandardCharsets.UTF_8));

            var schemaJson = ToolResultFormatter.buildSchemaJson(content);
            if (schemaJson != null) {
                var schemaPath = filePath.replaceAll("\\.json$", "") + ".schema.json";
                writeFile(sandbox, schemaPath, schemaJson.getBytes(StandardCharsets.UTF_8));
            }

            return result.withResult(ToolResultFormatter.buildSaveResultMessage(content, filePath));
        } catch (Exception e) {
            LOGGER.warn("save_to_file failed for tool {}, falling back to inline result", toolName, e);
            return result;
        }
    }

    private void writeFile(ai.core.sandbox.Sandbox sandbox, String path, byte[] bytes) throws Exception {
        if (sandbox != null) {
            sandbox.uploadFile(path, bytes);
            return;
        }
        var localPath = path.startsWith("/workspace/") ? path.substring("/workspace/".length()) : path;
        Files.write(Path.of(localPath), bytes);
    }

    private ToolCallResult validate(ToolCall tool, Map<String, Object> args, boolean useSandbox) {
        if (!useSandbox && Boolean.TRUE.equals(tool.isNeedAuth()) && !authenticated) {
            statusUpdater.accept(NodeStatus.WAITING_FOR_USER_INPUT);
            return ToolCallResult.failed("This tool call requires user authentication, please ask user to confirm it.");
        }

        var missingParams = tool.findMissingRequiredParams(args);
        if (!missingParams.isEmpty()) {
            LOGGER.warn("tool [{}] call rejected: missing required parameters: {}", tool.getName(), missingParams);
            return ToolCallResult.failed(Strings.format("Tool [{}] call failed: missing required parameters: [{}]. Please provide all required parameters and retry.",
                    tool.getName(), String.join(", ", missingParams)));
        }

        return null;
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private ToolCallResult executeWithTimeout(ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        var timeoutMs = tool.getTimeoutMs();

        var otelContext = Context.current();

        var threadRef = new AtomicReference<Thread>();
        var future = CompletableFuture.supplyAsync(() -> {
            threadRef.set(Thread.currentThread());
            try (var scope = otelContext.makeCurrent()) {
                return traceToolSpan(functionCall, tool.isSubAgent(), () -> tool.execute(functionCall.function.arguments, context));
            }
        }, AsyncToolTaskExecutor.getInstance().getExecutor());

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.warn("tool {} execution timed out after {}ms", functionCall.function.name, timeoutMs);
            future.cancel(true);
            return ToolCallResult.failed(Strings.format("tool call timed out after {}ms: {}", timeoutMs, functionCall.function.name));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            var asyncThread = threadRef.get();
            if (asyncThread != null) {
                asyncThread.interrupt();
            }
            return ToolCallResult.failed(Strings.format("tool call interrupted: {}", functionCall.function.name));
        } catch (ExecutionException e) {
            var cause = e.getCause();
            return ToolCallResult.failed(Strings.format("tool call failed<execute>:\n{}, cause:\n{}", JSON.toJSON(functionCall), cause != null ? cause.getMessage() : e.getMessage()), cause instanceof Exception ex ? ex : e);
        }
    }

    // Run the action inside a tool span nested under the triggering LLM span; runs the action directly when tracing is off.
    private ToolCallResult traceToolSpan(FunctionCall functionCall, boolean isSubAgent, Supplier<ToolCallResult> action) {
        if (tracer == null) {
            return action.get();
        }
        var llmSpanContext = llmSpanContextSupplier != null ? llmSpanContextSupplier.get() : null;
        return tracer.traceToolCall(functionCall.function.name, functionCall.function.arguments, llmSpanContext, isSubAgent, action);
    }

    private void handleAsyncResult(ToolCallResult result, ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        if (!result.isPending() && !result.isWaitingForInput()) {
            return;
        }

        var asyncTaskManager = context.getAsyncTaskManager();
        if (asyncTaskManager != null) {
            var asyncTask = new ToolCallAsyncTask(result.getTaskId(), tool, functionCall, result);
            asyncTaskManager.storeTask(asyncTask);
        }

        if (result.isPending()) {
            statusUpdater.accept(NodeStatus.WAITING_FOR_ASYNC_TASK);
        } else {
            statusUpdater.accept(NodeStatus.WAITING_FOR_USER_INPUT);
        }
    }
}
