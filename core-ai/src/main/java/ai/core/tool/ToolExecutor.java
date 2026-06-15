package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.telemetry.AgentTracer;
import ai.core.tool.async.AsyncToolTaskExecutor;
import core.framework.json.JSON;
import core.framework.util.Strings;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
        lifecycles.forEach(lc -> lc.beforeTool(functionCall, context));
        var result = executeWithoutLifecycle(tool, functionCall, context);
        lifecycles.forEach(lc -> lc.afterTool(functionCall, context, result));
        return result;
    }

    public ToolCallResult executeWithoutLifecycle(ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        return doExecute(tool, functionCall, context);
    }

    private ToolCallResult doExecute(ToolCall tool, FunctionCall functionCall, ExecutionContext context) {
        var sandbox = context.getSandbox();
        var useSandbox = sandbox != null && sandbox.shouldIntercept(tool.getName());

        var validationResult = validate(tool, functionCall, useSandbox);
        if (validationResult != null) {
            return validationResult;
        }

        LOGGER.debug("tool {}: {}", functionCall.function.name, functionCall.function.arguments);
        var startTime = System.currentTimeMillis();

        ToolCallResult result;
        if (useSandbox) {
            LOGGER.debug("sandbox intercepting tool: {}", tool.getName());
            // Wrap sandbox execution in a tool span too, otherwise sandbox-intercepted tools (e.g. a sub-agent's
            // file/bash operations) produce no TOOL span and never appear in the trace timeline.
            result = traceToolSpan(functionCall, () -> sandbox.execute(tool.getName(), functionCall.function.arguments, context));
            result.withStats("executionMode", "sandbox");
            result.withStats("sandboxId", sandbox.getId());
        } else {
            result = executeWithTimeout(tool, functionCall, context);
        }

        result.withToolName(tool.getName()).withDuration(System.currentTimeMillis() - startTime);
        handleAsyncResult(result, tool, functionCall, context);

        LOGGER.debug("tool {} completed in {}ms, stats: {}", result.getToolName(), result.getDurationMs(), result.getStats());
        return result;
    }

    private ToolCallResult validate(ToolCall tool, FunctionCall functionCall, boolean useSandbox) {
        if (!useSandbox && Boolean.TRUE.equals(tool.isNeedAuth()) && !authenticated) {
            statusUpdater.accept(NodeStatus.WAITING_FOR_USER_INPUT);
            return ToolCallResult.failed("This tool call requires user authentication, please ask user to confirm it.");
        }

        var missingParams = tool.findMissingRequiredParams(functionCall.function.arguments);
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
                return traceToolSpan(functionCall, () -> tool.execute(functionCall.function.arguments, context));
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
    private ToolCallResult traceToolSpan(FunctionCall functionCall, Supplier<ToolCallResult> action) {
        if (tracer == null) {
            return action.get();
        }
        var llmSpanContext = llmSpanContextSupplier != null ? llmSpanContextSupplier.get() : null;
        return tracer.traceToolCall(functionCall.function.name, functionCall.function.arguments, llmSpanContext, action);
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
