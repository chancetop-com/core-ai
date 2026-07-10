package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.CancelReason;
import ai.core.agent.CancellationException;
import ai.core.agent.CancellationToken;
import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
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
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class SubAgentToolCall extends ToolCall {
    public static final long DEFAULT_SUBAGENT_TIMEOUT_MS = 30 * 60 * 1000L;  // 30 minutes for subagent

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    private static CompletableFuture<String> runAgentAsync(Agent agent, String query, ExecutionContext context) {
        var executor = AsyncToolTaskExecutor.getInstance().getExecutor();
        var otelContext = Context.current();
        return CompletableFuture.supplyAsync(() -> {
            var scope = otelContext.makeCurrent();
            try {
                return agent.run(query, context);
            } finally {
                scope.close();
            }
        }, executor);
    }

    private Agent subAgent;
    private Supplier<Agent> agentFactory;

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        try {
            var args = parseArguments(arguments);
            var query = getStringValue(args, "query");

            if (query == null || query.isBlank()) {
                return ToolCallResult.failed("Parameter 'query' is required for subagent " + getName());
            }

            var parentToken = context.getCancellationToken();

            Agent agent = resolveAgent();

            // isolate: prevent message bubbling to parent
            // setting parent to null is sufficient — useGroupContext also checks parent != null
            agent.setParentNode(null);

            // create child token so parent cancel propagates but child cancel stays local
            var childToken = parentToken != null ? parentToken.createChild() : null;
            if (childToken != null) {
                context.setCancellationToken(childToken);
            }

            var future = runAgentAsync(agent, query, context);
            return getAgentResult(future, childToken, parentToken, agent, context);
        } catch (Exception e) {
            return ToolCallResult.failed("Subagent '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName());
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("SUB_AGENT_ENTRY_POINT_ERROR", "SubAgentToolCall requires ExecutionContext for execution");
    }

    private ToolCallResult getAgentResult(CompletableFuture<String> future, CancellationToken childToken,
                                           CancellationToken parentToken, Agent agent, ExecutionContext context) {
        try {
            var result = childToken != null
                    ? childToken.orCancel(future, getTimeoutMs())
                    : future.get(getTimeoutMs(), TimeUnit.MILLISECONDS);

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
        if (ctx != null) {
            var taskManager = ctx.getTaskManager();
            if (taskManager != null) {
                taskManager.cancelAll();
            }
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

    public static class Builder extends ToolCall.Builder<Builder, SubAgentToolCall> {
        private Agent subAgent;
        private Supplier<Agent> agentFactory;

        // singleton mode

        public Builder subAgent(Agent subAgent, Class<?>... classes) {
            return subAgent(subAgent, ToolCallParameters.of(classes));
        }

        public Builder subAgent(Agent subAgent) {
            return subAgent(subAgent, List.of(ToolCallParameter.builder()
                    .name("query")
                    .description("The query or instruction to send to the sub-agent.")
                    .required(true)
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

        // factory mode

        public Builder agentFactory(String name, String description, Supplier<Agent> factory) {
            this.agentFactory = factory;
            name(name);
            description(description);
            parameters(List.of(ToolCallParameter.builder()
                    .name("query")
                    .description("The query or instruction to send to the sub-agent.")
                    .required(true)
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
            return toolCall;
        }
    }
}
