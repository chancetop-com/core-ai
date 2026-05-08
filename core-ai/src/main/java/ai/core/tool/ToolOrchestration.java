package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.internal.AgentHelper;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.tool.async.AsyncToolTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    public ToolOrchestration(List<ToolCall> toolCalls, List<AbstractLifecycle> lifecycles, ToolExecutor toolExecutor, ExecutionContext context) {
        this(toolCalls, lifecycles, toolExecutor, context, DEFAULT_MAX_CONCURRENCY);
    }

    public ToolOrchestration(List<ToolCall> toolCalls, List<AbstractLifecycle> lifecycles, ToolExecutor toolExecutor, ExecutionContext context, int maxConcurrency) {
        this.toolExecutor = toolExecutor;
        this.lifecycles = lifecycles;
        this.context = context;
        this.maxConcurrency = maxConcurrency;
        this.groupIndex = getGroupIndex(toolCalls);
    }

    private Map<String, String> getGroupIndex(List<ToolCall> toolCalls) {
        Map<String, String> groupIndex = new LinkedHashMap<>();
        for (var tc : toolCalls) {
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
                .map(tc -> submitTool(semaphore, errored, group, tc))
                .toList();

        var results = new ArrayList<ToolCallResult>();
        for (int i = 0; i < batch.size(); i++) {
            results.add(awaitResult(futures.get(i), errored));
        }
        return results;
    }

    private CompletableFuture<ToolCallResult> submitTool(Semaphore semaphore, AtomicBoolean errored, String group, FunctionCall tc) {
        var executor = AsyncToolTaskExecutor.getInstance().getExecutor();
        return CompletableFuture.supplyAsync(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolCallResult.failed("interrupted");
            }
            try {
                if (errored.get()) {
                    return ToolCallResult.failed("Skipped: previous tool in group '" + group + "' failed");
                }
                var result = toolExecutor.executeWithoutLifecycle(tc, context);
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

    private List<Message> executeOne(FunctionCall tc) {
        var msgs = new ArrayList<Message>();
        var result = toolExecutor.execute(tc, context);
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
}
