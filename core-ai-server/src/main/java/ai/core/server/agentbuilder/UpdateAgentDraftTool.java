package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.agent.AgentDatasetConfigView;
import ai.core.api.server.agent.SandboxConfigView;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.DefinitionType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public final class UpdateAgentDraftTool extends ToolCall {
    public static final String TOOL_NAME = "update_agent_draft";

    public static UpdateAgentDraftTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new UpdateAgentDraftTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private UpdateAgentDraftTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    @Override
    @SuppressWarnings({"unchecked", "checkstyle:MethodLength", "PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar"})
    public ToolCallResult execute(String text, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);
            var agentId = (String) args.get("agent_id");

            if (agentId == null || agentId.isBlank()) {
                return ToolCallResult.failed("agent_id is required", null)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var entity = agentDefinitionService.getEntity(agentId);
            if (entity.type != DefinitionType.AGENT) {
                return ToolCallResult.failed("This tool only updates AGENT type agents. For LLM_CALL type, use the LLM Call Builder.", null)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var request = new UpdateAgentRequest();
            request.name = (String) args.get("name");
            request.description = (String) args.get("description");
            request.systemPrompt = (String) args.get("system_prompt");
            request.systemPromptId = (String) args.get("system_prompt_id");
            request.model = (String) args.get("model");

            var temperatureObj = args.get("temperature");
            if (temperatureObj instanceof Number number) {
                request.temperature = number.doubleValue();
            }

            var maxTurnsObj = args.get("max_turns");
            if (maxTurnsObj instanceof Number number) {
                request.maxTurns = number.intValue();
            }

            var timeoutObj = args.get("timeout_seconds");
            if (timeoutObj instanceof Number number) {
                request.timeoutSeconds = number.intValue();
            }

            var toolIds = (List<String>) args.get("tool_ids");
            if (toolIds != null && !toolIds.isEmpty()) {
                var toolRefs = new ArrayList<ToolRefView>();
                for (var toolId : toolIds) {
                    var ref = new ToolRefView();
                    ref.id = toolId;
                    toolRefs.add(ref);
                }
                request.tools = toolRefs;
            }

            request.inputTemplate = (String) args.get("input_template");
            request.multiModalModel = (String) args.get("multi_modal_model");

            // New fields
            var subAgentIds = (List<String>) args.get("subagent_ids");
            if (subAgentIds != null) {
                request.subAgentIds = subAgentIds;
            }

            var skillIds = (List<String>) args.get("skill_ids");
            if (skillIds != null) {
                request.skillIds = skillIds;
            }

            var variables = (Map<String, String>) args.get("variables");
            if (variables != null) {
                request.variables = variables;
            }

            var enableMemoryObj = args.get("enable_memory");
            if (enableMemoryObj instanceof Boolean b) {
                request.enableMemory = b;
            }

            var responseSchema = (String) args.get("response_schema");
            if (responseSchema != null) {
                request.responseSchema = responseSchema;
            }

            // sandbox_config (Map -> SandboxConfigView)
            var sandboxConfigObj = args.get("sandbox_config");
            if (sandboxConfigObj instanceof Map<?, ?> sandboxMap) {
                var sc = new SandboxConfigView();
                var enabledObj = sandboxMap.get("enabled");
                if (enabledObj instanceof Boolean b) sc.enabled = b;
                sc.image = (String) sandboxMap.get("image");
                var memoryLimitObj = sandboxMap.get("memory_limit_mb");
                if (memoryLimitObj instanceof Number n) sc.memoryLimitMb = n.intValue();
                var cpuLimitObj = sandboxMap.get("cpu_limit_millicores");
                if (cpuLimitObj instanceof Number n) sc.cpuLimitMillicores = n.intValue();
                var timeoutSecObj = sandboxMap.get("timeout_seconds");
                if (timeoutSecObj instanceof Number n) sc.timeoutSeconds = n.intValue();
                var networkEnabledObj = sandboxMap.get("network_enabled");
                if (networkEnabledObj instanceof Boolean b) sc.networkEnabled = b;
                sc.gitRepoUrl = (String) sandboxMap.get("git_repo_url");
                sc.gitBranch = (String) sandboxMap.get("git_branch");
                sc.tmpSizeLimit = (String) sandboxMap.get("tmp_size_limit");
                var maxAsyncObj = sandboxMap.get("max_async_tasks");
                if (maxAsyncObj instanceof Number n) sc.maxAsyncTasks = n.intValue();
                sc.envVars = (Map<String, String>) sandboxMap.get("env_vars");
                request.sandboxConfig = sc;
            }

            // dataset_config (List<Map> -> List<AgentDatasetConfigView>)
            var datasetConfigObj = args.get("dataset_config");
            if (datasetConfigObj instanceof List<?> datasetList) {
                var configs = new ArrayList<AgentDatasetConfigView>();
                for (var item : datasetList) {
                    if (item instanceof Map<?, ?> dm) {
                        var dc = new AgentDatasetConfigView();
                        dc.datasetId = (String) dm.get("dataset_id");
                        dc.permission = (String) dm.get("permission");
                        var isOutputObj = dm.get("is_output");
                        if (isOutputObj instanceof Boolean b) dc.isOutput = b;
                        if (dc.datasetId != null) {
                            configs.add(dc);
                        }
                    }
                }
                if (!configs.isEmpty()) {
                    request.datasetConfig = configs;
                }
            }

            var userId = context != null ? context.getUserId() : null;
            var view = agentDefinitionService.update(agentId, request, userId);

            var result = new StringBuilder(256);
            result.append("Agent draft updated successfully!\n\n")
                .append("ID: ").append(view.id).append("\n")
                .append("Name: ").append(view.name).append("\n")
                .append("Status: ").append(view.status).append("\n\n")
                .append("Fields updated:\n");
            if (request.name != null) result.append("  - name\n");
            if (request.description != null) result.append("  - description\n");
            if (request.systemPrompt != null) result.append("  - system_prompt\n");
            if (request.systemPromptId != null) result.append("  - system_prompt_id\n");
            if (request.model != null) result.append("  - model\n");
            if (request.temperature != null) result.append("  - temperature\n");
            if (request.maxTurns != null) result.append("  - max_turns\n");
            if (request.timeoutSeconds != null) result.append("  - timeout_seconds\n");
            if (request.tools != null) result.append("  - tools\n");
            if (request.inputTemplate != null) result.append("  - input_template\n");
            if (request.multiModalModel != null) result.append("  - multi_modal_model\n");
            if (request.subAgentIds != null) result.append("  - subagent_ids\n");
            if (request.skillIds != null) result.append("  - skill_ids\n");
            if (request.variables != null) result.append("  - variables\n");
            if (request.enableMemory != null) result.append("  - enable_memory\n");
            if (request.responseSchema != null) result.append("  - response_schema\n");
            if (request.sandboxConfig != null) result.append("  - sandbox_config\n");
            if (request.datasetConfig != null) result.append("  - dataset_config\n");

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Update agent draft failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @SuppressWarnings("unchecked")
    private static class Builder extends ToolCall.Builder<Builder, UpdateAgentDraftTool> {
        private final UpdateAgentDraftTool tool;

        Builder(UpdateAgentDraftTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Update an existing draft agent. Only the fields you provide will be changed; others remain unchanged. Use this instead of creating a new draft when you need to modify an existing one.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "agent_id", "The ID of the draft agent to update").required(),
                ToolCallParameters.ParamSpec.of(String.class, "name", "New name for the agent (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "description", "New description (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt", "New system prompt (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt_id", "New system prompt template ID (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "model", "New LLM model (optional)"),
                ToolCallParameters.ParamSpec.of(Double.class, "temperature", "New temperature 0-1 (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "max_turns", "New maximum conversation turns (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "timeout_seconds", "New timeout in seconds (optional)"),
                ToolCallParameters.ParamSpec.of(List.class, "tool_ids", "New list of builtin tool IDs (optional, replaces existing)"),
                ToolCallParameters.ParamSpec.of(String.class, "input_template", "New input template (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "multi_modal_model", "New multimodal model (optional, set to empty string to clear)"),
                // New fields
                ToolCallParameters.ParamSpec.of(List.class, "subagent_ids", "New list of sub-agent IDs (optional, replaces existing)"),
                ToolCallParameters.ParamSpec.of(List.class, "skill_ids", "New list of skill IDs (optional, replaces existing)"),
                ToolCallParameters.ParamSpec.of(Map.class, "sandbox_config", "New sandbox configuration object (optional). Fields: enabled (Boolean), image (String), memory_limit_mb (Integer), cpu_limit_millicores (Integer), timeout_seconds (Integer), network_enabled (Boolean), git_repo_url (String), git_branch (String), tmp_size_limit (String), max_async_tasks (Integer), env_vars (Map<String,String>)"),
                ToolCallParameters.ParamSpec.of(List.class, "dataset_config", "New list of dataset configuration objects (optional). Each item: {dataset_id: String, permission: 'READ'|'WRITE'|'FULL', is_output: Boolean}"),
                ToolCallParameters.ParamSpec.of(Map.class, "variables", "New input variable mappings (optional, Map<String,String>)"),
                ToolCallParameters.ParamSpec.of(Boolean.class, "enable_memory", "Enable agent memory (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "response_schema", "New response JSON Schema for structured output (optional)")
            ));
            build(tool);
        }
    }
}
