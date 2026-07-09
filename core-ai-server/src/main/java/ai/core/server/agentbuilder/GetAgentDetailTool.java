package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.Map;

public final class GetAgentDetailTool extends ToolCall {
    public static final String TOOL_NAME = "get_agent_detail";

    public static GetAgentDetailTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new GetAgentDetailTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private GetAgentDetailTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);
            var agentId = (String) args.get("agent_id");

            if (agentId == null || agentId.isBlank()) {
                return ToolCallResult.failed("agent_id is required", null)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var view = agentDefinitionService.get(agentId);

            var result = new StringBuilder(1024);
            result.append("Agent Detail:\n\n");
            result.append("ID: ").append(view.id).append("\n");
            result.append("Name: ").append(view.name).append("\n");
            result.append("Description: ").append(view.description != null ? view.description : "").append("\n");
            result.append("Status: ").append(view.status).append("\n");
            result.append("Type: ").append(view.type).append("\n");
            result.append("Created by: ").append(view.createdBy != null ? view.createdBy : "").append("\n");

            if (view.systemPrompt != null) {
                result.append("\nSystem Prompt:\n").append(view.systemPrompt).append("\n");
            }
            if (view.systemPromptId != null) {
                result.append("\nSystem Prompt ID: ").append(view.systemPromptId).append("\n");
            }
            if (view.model != null) {
                result.append("\nModel: ").append(view.model).append("\n");
            }
            if (view.multiModalModel != null) {
                result.append("Multi-modal Model: ").append(view.multiModalModel).append("\n");
            }
            if (view.temperature != null) {
                result.append("Temperature: ").append(view.temperature).append("\n");
            }
            if (view.maxTurns != null) {
                result.append("Max Turns: ").append(view.maxTurns).append("\n");
            }
            if (view.timeoutSeconds != null) {
                result.append("Timeout: ").append(view.timeoutSeconds).append("s\n");
            }
            result.append("Enable Memory: ").append(Boolean.TRUE.equals(view.enableMemory)).append("\n");

            if (view.tools != null && !view.tools.isEmpty()) {
                result.append("\nTools:\n");
                for (var tool : view.tools) {
                    result.append("  - ").append(tool.id);
                    if (tool.type != null) result.append(" (").append(tool.type).append(")");
                    result.append("\n");
                }
            }

            if (view.subAgents != null && !view.subAgents.isEmpty()) {
                result.append("\nSub-Agents:\n");
                for (var sa : view.subAgents) {
                    result.append("  - ").append(sa.name).append(" (").append(sa.id).append(")\n");
                }
            }

            if (view.skills != null && !view.skills.isEmpty()) {
                result.append("\nSkills:\n");
                for (var skill : view.skills) {
                    result.append("  - ").append(skill.name).append(" (").append(skill.id).append(")\n");
                }
            }

            if (view.inputTemplate != null) {
                result.append("\nInput Template: ").append(view.inputTemplate).append("\n");
            }
            if (view.variables != null && !view.variables.isEmpty()) {
                result.append("\nVariables:\n");
                for (var entry : view.variables.entrySet()) {
                    result.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            if (view.responseSchema != null) {
                result.append("\nResponse Schema: ").append(view.responseSchema).append("\n");
            }

            // Sandbox config
            if (view.sandboxConfig != null) {
                result.append("\nSandbox Config:\n");
                var sc = view.sandboxConfig;
                if (sc.enabled != null) result.append("  Enabled: ").append(sc.enabled).append("\n");
                if (sc.image != null) result.append("  Image: ").append(sc.image).append("\n");
                if (sc.memoryLimitMb != null) result.append("  Memory Limit: ").append(sc.memoryLimitMb).append(" MB\n");
                if (sc.cpuLimitMillicores != null) result.append("  CPU Limit: ").append(sc.cpuLimitMillicores).append(" millicores\n");
                if (sc.timeoutSeconds != null) result.append("  Timeout: ").append(sc.timeoutSeconds).append("s\n");
                if (sc.networkEnabled != null) result.append("  Network Enabled: ").append(sc.networkEnabled).append("\n");
                if (sc.gitRepoUrl != null) result.append("  Git Repo: ").append(sc.gitRepoUrl).append("\n");
                if (sc.gitBranch != null) result.append("  Git Branch: ").append(sc.gitBranch).append("\n");
                if (sc.tmpSizeLimit != null) result.append("  Tmp Size Limit: ").append(sc.tmpSizeLimit).append("\n");
                if (sc.maxAsyncTasks != null) result.append("  Max Async Tasks: ").append(sc.maxAsyncTasks).append("\n");
                if (sc.envVars != null && !sc.envVars.isEmpty()) {
                    result.append("  Env Vars:\n");
                    for (var entry : sc.envVars.entrySet()) {
                        result.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                }
            }

            // Dataset config
            if (view.datasetConfig != null && !view.datasetConfig.isEmpty()) {
                result.append("\nDataset Config:\n");
                for (var dc : view.datasetConfig) {
                    result.append("  - Dataset ID: ").append(dc.datasetId);
                    result.append(", Permission: ").append(dc.permission);
                    if (dc.isOutput != null) result.append(", Is Output: ").append(dc.isOutput);
                    result.append("\n");
                }
            }

            if (view.createdAt != null) {
                result.append("\nCreated At: ").append(view.createdAt).append("\n");
            }
            if (view.updatedAt != null) {
                result.append("Updated At: ").append(view.updatedAt).append("\n");
            }
            if (view.publishedAt != null) {
                result.append("Published At: ").append(view.publishedAt).append("\n");
            }

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Get agent detail failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static class Builder extends ToolCall.Builder<Builder, GetAgentDetailTool> {
        private final GetAgentDetailTool tool;

        Builder(GetAgentDetailTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Get the complete details of a single agent by its ID, including all configuration: system prompt, model settings, tools, sub-agents, skills, sandbox config, dataset config, memory settings, and more.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "agent_id", "The ID of the agent to retrieve").required()
            ));
            build(tool);
        }
    }
}
