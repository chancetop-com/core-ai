package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.telemetry.AgentTracer;
import ai.core.tool.async.AsyncToolTaskExecutor;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class ToolExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolExecutor.class);

    private final List<ToolCall> toolCalls;
    private final List<AbstractLifecycle> lifecycles;
    private final AgentTracer tracer;
    private final Consumer<NodeStatus> statusUpdater;
    private boolean authenticated = false;

    public ToolExecutor(List<ToolCall> toolCalls, List<AbstractLifecycle> lifecycles, AgentTracer tracer, Consumer<NodeStatus> statusUpdater) {
        this.toolCalls = toolCalls;
        this.lifecycles = lifecycles;
        this.tracer = tracer;
        this.statusUpdater = statusUpdater;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public ToolCallResult execute(FunctionCall functionCall, ExecutionContext context) {
        // before
        lifecycles.forEach(lc -> lc.beforeTool(functionCall, context));

        // execute tool
        var result = doExecute(functionCall, context);
        // after
        lifecycles.forEach(lc -> lc.afterTool(functionCall, context, result));
        return result;
    }

    private ToolCallResult doExecute(FunctionCall functionCall, ExecutionContext context) {
        var optional = toolCalls.stream()
                .filter(v -> v.getName().equalsIgnoreCase(functionCall.function.name))
                .findFirst();

        if (optional.isEmpty())
            optional = toolCalls.stream().filter(v -> v.getName().endsWith(functionCall.function.name)).findFirst();

        if (optional.isEmpty()) {
            return ToolCallResult.failed("tool not found: " + functionCall.function.name);
        }

        var tool = optional.get();
        // Check authentication
        if (Boolean.TRUE.equals(tool.isNeedAuth()) && !authenticated) {
            statusUpdater.accept(NodeStatus.WAITING_FOR_USER_INPUT);
            return ToolCallResult.failed("This tool call requires user authentication, please ask user to confirm it.");
        }

        LOGGER.info("tool {}: {}", functionCall.function.name, functionCall.function.arguments);
        var startTime = System.currentTimeMillis();

        var result = executeWithTimeout(tool, functionCall, context);

        // Set stats
        result.withToolName(tool.getName()).withDuration(System.currentTimeMillis() - startTime);
        // Handle async results
        handleAsyncResult(result, tool, functionCall, context);

        // Log stats
        LOGGER.info("tool {} completed in {}ms, stats: {}", result.getToolName(), result.getDurationMs(), result.getStats());
        return result;
    }

    private ToolCallResult executeWithTimeout(ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        var timeoutMs = tool.getTimeoutMs();

        var future = CompletableFuture.supplyAsync(() -> {
            if (tracer != null) {
                return tracer.traceToolCall(
                        functionCall.function.name,
                        functionCall.function.arguments,
                        () -> tool.execute(functionCall.function.arguments, context)
                );
            } else {
                return tool.execute(functionCall.function.arguments, context);
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
            return ToolCallResult.failed(Strings.format("tool call interrupted: {}", functionCall.function.name));
        } catch (ExecutionException e) {
            var cause = e.getCause();
            return ToolCallResult.failed(Strings.format("tool call failed<execute>:\n{}, cause:\n{}", JSON.toJSON(functionCall), cause != null ? cause.getMessage() : e.getMessage()), cause instanceof Exception ex ? ex : e);
        }
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
