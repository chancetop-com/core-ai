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
import java.util.Map;

/**
 * @author stephen
 */
public class SubAgentToolCall extends ToolCall {
    public static final long DEFAULT_SUBAGENT_TIMEOUT_MS = 30 * 60 * 1000L;  // 30 minutes for subagent

    public static Builder builder() {
        return new Builder();
    }

    private Agent subAgent;

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        try {
            var args = JSON.fromJSON(Map.class, arguments);
            var query = (String) args.get("query");

            if (query == null || query.isBlank()) {
                return ToolCallResult.failed("Parameter 'query' is required for subagent " + getName());
            }

            var result = subAgent.run(query, context);

            // Handle subagent status propagation
            var subAgentStatus = subAgent.getNodeStatus();

            if (subAgentStatus == NodeStatus.WAITING_FOR_USER_INPUT) {
                // Propagate waiting for user input status
                return ToolCallResult.waitingForInput(subAgent.getId(), result)
                        .withToolName(getName())
                        .withStats("subagent_name", subAgent.getName())
                        .withStats("subagent_status", subAgentStatus.name());
            }

            if (subAgentStatus == NodeStatus.FAILED) {
                return ToolCallResult.failed("Subagent '" + getName() + "' failed: " + result)
                        .withToolName(getName());
            }

            return ToolCallResult.completed(result)
                    .withToolName(getName())
                    .withStats("subagent_name", subAgent.getName())
                    .withStats("subagent_token_usage", subAgent.getCurrentTokenUsage());

        } catch (Exception e) {
            return ToolCallResult.failed("Subagent '" + getName() + "' execution error: " + e.getMessage(), e)
                    .withToolName(getName());
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        throw new AgentRuntimeException("SUB_AGENT_ENTRY_POINT_ERROR", "SubAgentToolCall requires ExecutionContext for execution");
    }

    public Agent getSubAgent() {
        return subAgent;
    }

    public NodeStatus getSubAgentStatus() {
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
            // Use agent's name and description as tool name and description
            name(subAgent.getName());
            description(subAgent.getDescription());
            // Define the query parameter
            parameters(parameters);
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public SubAgentToolCall build() {
            if (subAgent == null) {
                throw new RuntimeException("subAgent is required for SubAgentToolCall");
            }
            var toolCall = new SubAgentToolCall();
            super.build(toolCall);
            toolCall.subAgent = this.subAgent;
            return toolCall;
        }
    }
}
