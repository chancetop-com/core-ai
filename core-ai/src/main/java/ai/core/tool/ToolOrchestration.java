package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.internal.AgentHelper;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.tool.async.AsyncToolTaskExecutor;
import io.opentelemetry.context.Context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author lim chen
 * Orchestrates concurrent tool execution with concurrency-group-based partitioning.
 * Tools with the same non-null {@link ToolCall#getConcurrencyGroup()} can run concurrently
 * within the same batch. Tools with null group (exclusive) each get their own batch
 * and act as a barrier — subsequent batches wait for them to complete.
 */
public class ToolOrchestration {
    private static final int DEFAULT_MAX_CONCURRENCY = 10;

    private final int maxConcurrency;
    private final List<AbstractLifecycle> lifecycles;
    private final ToolExecutor toolExecutor;
    private final ExecutionContext context;
    private final Map<String, String> groupIndex;
    private final Map<String, ToolCall> toolIndex;



    public ToolOrchestration(Map<String, ToolCall> dispatchMap, List<AbstractLifecycle> lifecycles, ToolExecutor toolExecutor, ExecutionContext context) {
        this.toolExecutor = toolExecutor;
        this.lifecycles = lifecycles;
        this.context = context;
        this.maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        this.toolIndex = dispatchMap;
        this.groupIndex = getGroupIndexFromMap(dispatchMap.values());
    }

    private Map<String, String> getGroupIndexFromMap(Collection<ToolCall> tools) {
        Map<String, String> groupIndex = new LinkedHashMap<>();
        for (var tc : tools) {
            if (tc.getConcurrencyGroup() != null) {
                groupIndex.put(tc.getName(), tc.getConcurrencyGroup());
            }
        }
        return groupIndex;
    }

    public List<Message> execute(List<FunctionCall> toolCalls) {
        var batches = partition(toolCalls);
        List<Message> allMessages = new ArrayList<>();
        for (var batch : batches) {
            context.throwIfCancelled();
            allMessages.addAll(executeBatch(batch));
        }
        return allMessages;
    }

    private List<List<FunctionCall>> partition(List<FunctionCall> toolCalls) {
        var batches = new ArrayList<List<FunctionCall>>();
        var current = new ArrayList<FunctionCall>();
        String currentGroup = null;

        for (var tc : toolCalls) {
            var group = resolveGroup(tc);
            if (!isCallConcurrencySafe(tc)) {
                group = null;
            }
            boolean sameGroup = group != null && group.equals(currentGroup);

            if (!sameGroup) {
                if (!current.isEmpty()) {
                    batches.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
                currentGroup = group;
            }
            current.add(tc);
        }

        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private List<Message> executeBatch(List<FunctionCall> batch) {
        if (batch.size() == 1) {
            return executeOne(batch.getFirst());
        }

        var group = resolveGroup(batch.getFirst());
        lifecycles.forEach(lc -> lc.beforeBatch(group, batch, context));
        try {
            for (var tc : batch) {
                lifecycles.forEach(lc -> lc.beforeTool(tc, context));
            }

            var toolResults = runBatchConcurrently(batch, group);

            var messages = new ArrayList<Message>();
            for (int i = 0; i < batch.size(); i++) {
                var tc = batch.get(i);
                var tr = toolResults.get(i);
                lifecycles.forEach(lc -> lc.afterTool(tc, context, tr));
                messages.addAll(buildMessages(tc, tr));
            }
            return messages;
        } finally {
            lifecycles.forEach(lc -> lc.afterBatch(group, batch, context));
        }
    }

    private List<ToolCallResult> runBatchConcurrently(List<FunctionCall> batch, String group) {
        var semaphore = new Semaphore(maxConcurrency);
        var errored = new AtomicBoolean(false);
        var futures = batch.stream()
                .map(tc -> {
                    if (context.isCancelled()) {
                        var token = context.getCancellationToken();
                        return CompletableFuture.completedFuture(
                                ToolCallResult.failed("cancelled: " + (token != null ? token.getReason() : "unknown")));
                    }
                    return submitTool(semaphore, errored, group, tc);
                })
                .toList();

        var results = new ArrayList<ToolCallResult>();
        for (var future : futures) {
            results.add(awaitResult(future, errored));
        }
        return results;
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private CompletableFuture<ToolCallResult> submitTool(Semaphore semaphore, AtomicBoolean errored, String group, FunctionCall tc) {
        var executor = AsyncToolTaskExecutor.getInstance().getExecutor();
        // Capture OTel context on the calling thread so tool spans created in the virtual thread
        // still nest under the agent/LLM span that triggered this batch.
        var otelContext = Context.current();
        return CompletableFuture.supplyAsync(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolCallResult.failed("interrupted");
            }
            try (var scope = otelContext.makeCurrent()) {
                if (errored.get()) {
                    return ToolCallResult.failed("Skipped: previous tool in group '" + group + "' failed");
                }
                var tool = resolveTool(tc);
                if (tool == null) {
                    errored.set(true);
                    return ToolCallResult.failed("tool not found: " + tc.function.name);
                }
                var result = toolExecutor.executeWithoutLifecycle(tool, tc, context);
                if (result.isFailed()) {
                    errored.set(true);
                }
                return result;
            } finally {
                semaphore.release();
            }
        }, executor);
    }

    private ToolCallResult awaitResult(CompletableFuture<ToolCallResult> future, AtomicBoolean errored) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errored.set(true);
            return ToolCallResult.failed("tool call interrupted");
        } catch (ExecutionException e) {
            errored.set(true);
            var cause = e.getCause();
            return ToolCallResult.failed("tool call failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
        }
    }

    private ToolCall resolveTool(FunctionCall tc) {
        var tool = toolIndex.get(tc.function.name);
        if (tool != null) return tool;
        for (var entry : toolIndex.entrySet()) {
            if (entry.getKey().endsWith(tc.function.name)) return entry.getValue();
        }
        return null;
    }

    private List<Message> executeOne(FunctionCall tc) {
        var msgs = new ArrayList<Message>();
        var tool = resolveTool(tc);
        if (tool == null) {
            msgs.add(buildToolNotFoundMessage(tc));
            return msgs;
        }
        var result = toolExecutor.execute(tool, tc, context);
        if (result.isDirectReturn()) {
            msgs.add(AgentHelper.buildToolMessage(tc, result, true));
            msgs.add(Message.of(RoleType.ASSISTANT, result.toResultForLLM()));
        } else {
            msgs.add(AgentHelper.buildToolMessage(tc, result));
        }
        return msgs;
    }

    private List<Message> buildMessages(FunctionCall tc, ToolCallResult result) {
        var msgs = new ArrayList<Message>();
        if (result.isDirectReturn()) {
            msgs.add(AgentHelper.buildToolMessage(tc, result, true));
            msgs.add(Message.of(RoleType.ASSISTANT, result.toResultForLLM()));
        } else {
            msgs.add(AgentHelper.buildToolMessage(tc, result));
        }
        return msgs;
    }

    private String resolveGroup(FunctionCall tc) {
        return groupIndex.get(tc.function.name);
    }

    private boolean isCallConcurrencySafe(FunctionCall tc) {
        var tool = toolIndex.get(tc.function.name);
        if (tool == null) return false;
        return tool.isConcurrencySafe(tc.function.arguments);
    }

    private Message buildToolNotFoundMessage(FunctionCall tc) {
        return AgentHelper.buildToolMessage(tc, ToolCallResult.failed("tool not found: " + tc.function.name));
    }
}
