package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.CancelReason;
import ai.core.agent.CancellationException;
import ai.core.agent.CancellationToken;
import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
import ai.core.agent.subagent.SubagentThread;
import ai.core.agent.subagent.SubagentThreadKind;
import ai.core.agent.subagent.SubagentThreadRegistry;
import ai.core.agent.subagent.SubagentThreadUnavailableException;
import ai.core.llm.domain.RoleType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import io.opentelemetry.context.Context;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class SubAgentToolCall extends ToolCall {
    public static final long DEFAULT_SUBAGENT_TIMEOUT_MS = 30 * 60 * 1000L;  // 30 minutes for subagent
    private static final String THREAD_TASK_ID_PREFIX = "agent:";

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    private static CompletableFuture<String> runAgentAsync(Agent agent, String query, ExecutionContext context, Runnable onFinish) {
        var executor = AsyncToolTaskExecutor.getInstance().getExecutor();
        var otelContext = Context.current();
        return CompletableFuture.supplyAsync(() -> {
            var scope = otelContext.makeCurrent();
            try {
                return agent.run(query, context);
            } finally {
                scope.close();
                // runs when the agent truly stops, which may be after the tool call already returned
                // (timeout/cancel leave the run going); the session registry gate stays held until then
                onFinish.run();
            }
        }, executor);
    }

    private Agent subAgent;
    private Supplier<Agent> agentFactory;
    private ContextPolicy contextPolicy = ContextPolicy.SESSION;
    // serializes FRESH runs on the singleton instance; the registry gates SESSION runs
    private final AtomicBoolean freshRunning = new AtomicBoolean(false);

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        try {
            return doExecute(arguments, context);
        } catch (Exception e) {
            return ToolCallResult.failed("Subagent '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName());
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("SUB_AGENT_ENTRY_POINT_ERROR", "SubAgentToolCall requires ExecutionContext for execution");
    }

    private ToolCallResult doExecute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var query = getStringValue(args, "query");
        if (query == null || query.isBlank()) {
            return ToolCallResult.failed("Parameter 'query' is required for subagent " + getName());
        }

        // factory mode builds a fresh agent per call and FRESH serializes on the instance itself:
        // only the singleton SESSION mode has a session thread to gate through
        var registry = keepsSessionThread() ? context.getSubagentThreads() : null;
        var thread = registry == null ? null : resolveSessionThread(registry);
        if (isBusy(registry, thread)) {
            return ToolCallResult.failed("subagent '" + getName() + "' is still running from an earlier call; wait for it to finish");
        }

        Agent agent;
        try {
            agent = resolveAgentForThread(thread, registry);
        } catch (SubagentThreadUnavailableException e) {
            releaseOnFailure(registry, thread);
            return ToolCallResult.failed("subagent '" + getName() + "' could not restore its saved context: " + e.getMessage());
        }
        return runWithGate(agent, query, context, registry, thread);
    }

    private ToolCallResult runWithGate(Agent agent, String query, ExecutionContext context, SubagentThreadRegistry registry, SubagentThread thread) {
        if (contextPolicy == ContextPolicy.FRESH && agentFactory == null) {
            agent.reset();
        }
        Runnable onFinish = () -> {
            freshRunning.set(false);
            releaseThread(registry, thread);
        };
        return startAndAwait(agent, query, context, onFinish);
    }

    private ToolCallResult startAndAwait(Agent agent, String query, ExecutionContext context, Runnable onFinish) {
        var parentBudget = context.getRunBudget();
        if (parentBudget != null) {
            context.setRunBudget(parentBudget.childScope());
        }
        var handedToRun = false;
        try {
            // isolate: prevent message bubbling to parent
            // setting parent to null is sufficient — useGroupContext also checks parent != null
            agent.setParentNode(null);

            // create child token so parent cancel propagates but child cancel stays local
            var parentToken = context.getCancellationToken();
            var childToken = parentToken != null ? parentToken.createChild() : null;
            if (childToken != null) {
                context.setCancellationToken(childToken);
            }

            var future = runAgentAsync(agent, query, context, onFinish);
            handedToRun = true;
            return getAgentResult(future, childToken, parentToken, agent, context);
        } finally {
            if (parentBudget != null) {
                context.setRunBudget(parentBudget);
            }
            // the run never started (or could not be submitted); the gate must not stay held
            if (!handedToRun) {
                onFinish.run();
            }
        }
    }

    private boolean keepsSessionThread() {
        return contextPolicy == ContextPolicy.SESSION && agentFactory == null && subAgent != null;
    }

    /**
     * Finds this tool's session thread, registering the singleton agent on first use. A thread under
     * the same id owned by another tool is left alone — this call then runs ungated on its own instance.
     */
    private SubagentThread resolveSessionThread(SubagentThreadRegistry registry) {
        var taskId = THREAD_TASK_ID_PREFIX + getName();
        var thread = registry.find(taskId).orElse(null);
        if (thread != null && thread.kind() != SubagentThreadKind.AGENT_TOOL) {
            return null;
        }
        if (thread == null) {
            thread = registry.register(taskId, SubagentThreadKind.AGENT_TOOL, getName(), getName(), null, resolveAgent());
        }
        return thread;
    }

    private boolean isBusy(SubagentThreadRegistry registry, SubagentThread thread) {
        if (thread != null) {
            return !registry.tryAcquire(thread);
        }
        return registry == null && contextPolicy == ContextPolicy.FRESH && agentFactory == null
                && !freshRunning.compareAndSet(false, true);
    }

    private Agent resolveAgentForThread(SubagentThread thread, SubagentThreadRegistry registry) {
        if (thread == null) {
            return resolveAgent();
        }
        var agent = thread.agent();
        if (agent != null) {
            return agent;
        }
        return registry.rehydrate(thread, this::resolveAgent);
    }

    private void releaseOnFailure(SubagentThreadRegistry registry, SubagentThread thread) {
        freshRunning.set(false);
        if (registry != null && thread != null) {
            registry.abort(thread);
        }
    }

    private void releaseThread(SubagentThreadRegistry registry, SubagentThread thread) {
        if (registry == null || thread == null) return;
        try {
            registry.release(thread, thread.agent(), null);
        } finally {
            registry.persist(thread);
            registry.evictIfNeeded();
        }
    }

    private long resolveTimeoutMs(ExecutionContext context) {
        long timeoutMs = getTimeoutMs();
        var budget = context.getRunBudget();
        if (budget != null && budget.time() != null) {
            long remaining = budget.time().remainingMillis() - budget.time().reserveMillis();
            timeoutMs = Math.min(timeoutMs, Math.max(1, remaining));
        }
        return timeoutMs;
    }

    private ToolCallResult getAgentResult(CompletableFuture<String> future, CancellationToken childToken,
                                           CancellationToken parentToken, Agent agent, ExecutionContext context) {
        var timeoutMs = resolveTimeoutMs(context);
        try {
            var result = childToken != null
                    ? childToken.orCancel(future, timeoutMs)
                    : future.get(timeoutMs, TimeUnit.MILLISECONDS);

            var subAgentStatus = agent.getNodeStatus();

            if (subAgentStatus == NodeStatus.WAITING_FOR_USER_INPUT) {
                return withSubAgentStats(ToolCallResult.waitingForInput(agent.getId(), result), agent)
                        .withToolName(getName())
                        .withStats("subagent_status", subAgentStatus.name());
            }

            if (subAgentStatus == NodeStatus.FAILED) {
                return ToolCallResult.failed("Subagent '" + getName() + "' failed: " + result)
                        .withToolName(getName());
            }

            return withSubAgentStats(ToolCallResult.completed(result), agent)
                    .withToolName(getName())
                    .withStats("subagent_token_usage", agent.getCurrentTokenUsage());

        } catch (CancellationException e) {
            future.cancel(true);
            cancelChildBackgroundTasks(agent);
            return buildPartialOrCancelledResult(agent);
        } catch (TimeoutException e) {
            future.cancel(true);
            if (childToken != null) childToken.cancel(CancelReason.TIMEOUT);
            cancelChildBackgroundTasks(agent);
            return buildPartialOrCancelledResult(agent);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            cancelChildBackgroundTasks(agent);
            return buildPartialOrCancelledResult(agent);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) {
                return ToolCallResult.failed("Subagent '" + getName() + "' execution error: " + ex.getMessage(), ex)
                        .withToolName(getName());
            }
            return ToolCallResult.failed("Subagent '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName());
        } finally {
            if (childToken != null) {
                context.setCancellationToken(parentToken);
                childToken.disconnect();
            }
        }
    }

    private ToolCallResult buildPartialOrCancelledResult(Agent agent) {
        var partialOutput = agent.getOutput();

        if (partialOutput != null && !partialOutput.isBlank()) {
            return withSubAgentStats(ToolCallResult.completed(partialOutput), agent)
                    .withToolName(getName())
                    .withStats("subagent_status", "cancelled_with_partial_result")
                    .withStats("subagent_token_usage", agent.getCurrentTokenUsage());
        }

        var toolResultCount = agent.getMessages().stream()
                .filter(m -> m.role == RoleType.TOOL).count();
        if (toolResultCount > 0) {
            return withSubAgentStats(ToolCallResult.completed("(cancelled, but produced " + toolResultCount + " tool results)"), agent)
                    .withToolName(getName())
                    .withStats("subagent_status", "cancelled_with_tool_results");
        }

        return withSubAgentStats(ToolCallResult.failed("subagent '" + getName() + "' cancelled"), agent)
                .withToolName(getName());
    }

    private ToolCallResult withSubAgentStats(ToolCallResult result, Agent agent) {
        return result
                .withStats("subagent_id", agent.getId())
                .withStats("subagent_name", agent.getName());
    }

    private void cancelChildBackgroundTasks(Agent agent) {
        var ctx = agent.getExecutionContext();
        var taskManager = ctx.getTaskManager();
        if (taskManager != null) {
            taskManager.cancelAll();
        }
        for (var subAgent : agent.getSubAgents()) {
            subAgent.getSubAgent().cancel();
        }
    }

    private Agent resolveAgent() {
        if (agentFactory != null) return agentFactory.get();
        return this.subAgent;
    }

    public Agent getSubAgent() {
        return subAgent;
    }

    public NodeStatus getSubAgentStatus() {
        if (subAgent == null) return null;
        return subAgent.getNodeStatus();
    }

    @Override
    public boolean isSubAgent() {
        return true;
    }

    @Override
    public long getTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_SUBAGENT_TIMEOUT_MS;
    }

    /** Whether this subagent keeps its conversation context across calls in the session. */
    public enum ContextPolicy {
        /** The singleton agent and its messages are reused; context is persisted with the session. */
        SESSION,
        /** The conversation starts empty on every call (the wrapped agent is reset first). */
        FRESH
    }

    public static class Builder extends ToolCall.Builder<Builder, SubAgentToolCall> {
        private Agent subAgent;
        private Supplier<Agent> agentFactory;
        private ContextPolicy contextPolicy = ContextPolicy.SESSION;

        // singleton mode

        public Builder subAgent(Agent subAgent, Class<?>... classes) {
            return subAgent(subAgent, ToolCallParameters.of(classes));
        }

        public Builder subAgent(Agent subAgent) {
            return subAgent(subAgent, List.of(ToolCallParameter.builder()
                    .name("query")
                    .description("The query or instruction to send to the sub-agent.")
                    .required(Boolean.TRUE)
                    .build()
            ));
        }

        public Builder subAgent(Agent subAgent, List<ToolCallParameter> parameters) {
            this.subAgent = subAgent;
            name(subAgent.getName());
            description(subAgent.getDescription());
            parameters(parameters);
            return this;
        }

        public Builder contextPolicy(ContextPolicy contextPolicy) {
            this.contextPolicy = contextPolicy;
            return this;
        }

        // factory mode

        public Builder agentFactory(String name, String description, Supplier<Agent> factory) {
            this.agentFactory = factory;
            name(name);
            description(description);
            parameters(List.of(ToolCallParameter.builder()
                    .name("query")
                    .description("The query or instruction to send to the sub-agent.")
                    .required(Boolean.TRUE)
                    .build()
            ));
            return this;
        }


        @Override
        protected Builder self() {
            return this;
        }

        public SubAgentToolCall build() {
            if (subAgent == null && agentFactory == null) {
                throw new RuntimeException("Either subAgent or agentFactory is required for SubAgentToolCall");
            }
            sourceType("agent");
            var toolCall = new SubAgentToolCall();
            super.build(toolCall);
            toolCall.subAgent = this.subAgent;
            toolCall.agentFactory = this.agentFactory;
            toolCall.contextPolicy = this.contextPolicy;
            if (subAgent != null && contextPolicy == ContextPolicy.SESSION) {
                toolCall.setDescription(toolCall.getDescription()
                        + "\nThis subagent keeps its conversation context between calls in this session — after it answers, ask a follow-up with the same tool and it will remember what it did before.");
            }
            return toolCall;
        }
    }
}
