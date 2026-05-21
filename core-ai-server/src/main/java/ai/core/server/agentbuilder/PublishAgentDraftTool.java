package ai.core.server.agentbuilder;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;

/**
 * @author stephen
 */
public final class PublishAgentDraftTool extends ToolCall {
    public static final String TOOL_NAME = "publish_agent_draft";

    public static PublishAgentDraftTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new PublishAgentDraftTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private PublishAgentDraftTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);
            var agentId = (String) args.get("agent_id");

            if (agentId == null || agentId.isBlank()) {
                return ToolCallResult.failed("agent_id is required", null)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var view = agentDefinitionService.publish(agentId);

            var result = new StringBuilder(256);
            result.append("Agent published successfully!\n\n")
                .append("ID: ").append(view.id).append("\n")
                .append("Name: ").append(view.name).append("\n")
                .append("Status: PUBLISHED\n\n")
                .append("The agent is now available at: /agents/").append(view.id).append("\n")
                .append("You can test it by selecting this agent in the Chat page.");

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Publish agent failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, PublishAgentDraftTool> {
        private final PublishAgentDraftTool tool;

        Builder(PublishAgentDraftTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Publish an existing draft agent, making it available for use in Chat.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "agent_id", "The ID of the draft agent to publish").required()
            ));
            build(tool);
        }
    }
}
