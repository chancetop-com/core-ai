package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.NodeStatus;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.List;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class SubAgentToolCall extends ToolCall {
    public static final long DEFAULT_SUBAGENT_TIMEOUT_MS = 30 * 60 * 1000L;  // 30 minutes for subagent

    public static Builder builder() {
        return new Builder();
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

            Agent agent = resolveAgent();

            // isolate: prevent message bubbling to parent
            // setting parent to null is sufficient — useGroupContext also checks parent != null
            agent.setParentNode(null);

            var result = agent.run(query, context);

            // Handle subagent status propagation
            var subAgentStatus = agent.getNodeStatus();

            if (subAgentStatus == NodeStatus.WAITING_FOR_USER_INPUT) {
                return ToolCallResult.waitingForInput(agent.getId(), result)
                        .withToolName(getName())
                        .withStats("subagent_name", agent.getName())
                        .withStats("subagent_status", subAgentStatus.name());
            }

            if (subAgentStatus == NodeStatus.FAILED) {
                return ToolCallResult.failed("Subagent '" + getName() + "' failed: " + result)
                        .withToolName(getName());
            }

            return ToolCallResult.completed(result)
                    .withToolName(getName())
                    .withStats("subagent_name", agent.getName())
                    .withStats("subagent_token_usage", agent.getCurrentTokenUsage());

        } catch (Exception e) {
            return ToolCallResult.failed("Subagent '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName());
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("SUB_AGENT_ENTRY_POINT_ERROR", "SubAgentToolCall requires ExecutionContext for execution");
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
            var toolCall = new SubAgentToolCall();
            super.build(toolCall);
            toolCall.subAgent = this.subAgent;
            toolCall.agentFactory = this.agentFactory;
            return toolCall;
        }
    }
}
