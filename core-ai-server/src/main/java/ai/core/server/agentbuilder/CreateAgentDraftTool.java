package ai.core.server.agentbuilder;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.agent.AgentDatasetConfigView;
import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.SandboxConfigView;
import ai.core.server.agent.AgentDefinitionService;
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
public final class CreateAgentDraftTool extends ToolCall {
    public static final String TOOL_NAME = "create_agent_draft";
    @SuppressWarnings("PMD.MutableStaticState")
    public static String publicUrl;

    public static CreateAgentDraftTool create(AgentDefinitionService agentDefinitionService) {
        var tool = new CreateAgentDraftTool(agentDefinitionService);
        new Builder(tool).build();
        return tool;
    }

    private final AgentDefinitionService agentDefinitionService;

    private CreateAgentDraftTool(AgentDefinitionService agentDefinitionService) {
        this.agentDefinitionService = agentDefinitionService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text, ExecutionContext context) {
        var userId = context != null ? context.getUserId() : null;
        return doExecute(text, userId != null ? userId : "system");
    }

    @SuppressWarnings({"unchecked", "PMD.ConsecutiveLiteralAppends", "PMD.AppendCharacterWithChar"})
    private ToolCallResult doExecute(String text, String creator) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, text);

            var request = new CreateAgentRequest();
            request.name = (String) args.get("name");
            request.description = (String) args.get("description");
            request.systemPrompt = (String) args.get("system_prompt");
            request.systemPromptId = (String) args.get("system_prompt_id");
            request.model = (String) args.get("model");
            request.type = "AGENT";

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
                var toolRefs = new ArrayList<ai.core.api.server.tool.ToolRefView>();
                for (var toolId : toolIds) {
                    var ref = new ai.core.api.server.tool.ToolRefView();
                    ref.id = toolId;
                    toolRefs.add(ref);
                }
                request.tools = toolRefs;
            }

            request.inputTemplate = (String) args.get("input_template");
            request.multiModalModel = (String) args.get("multi_modal_model");

            // New fields
            request.subAgentIds = (List<String>) args.get("subagent_ids");
            request.skillIds = (List<String>) args.get("skill_ids");
            request.variables = (Map<String, String>) args.get("variables");

            var enableMemoryObj = args.get("enable_memory");
            if (enableMemoryObj instanceof Boolean b) {
                request.enableMemory = b;
            }

            request.responseSchema = (String) args.get("response_schema");

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

            var view = agentDefinitionService.create(request, creator);

            var result = new StringBuilder(256);
            result.append("Agent draft created successfully!\n\n")
                .append("ID: ").append(view.id).append("\n")
                .append("Name: ").append(view.name).append("\n")
                .append("Status: DRAFT\n")
                .append("Type: AGENT\n\n")
                .append("Edit the agent: ").append(publicUrl).append("/agents/").append(view.id).append("/edit\n")
                .append("You can now review the draft with the user and publish it when ready.");

            return ToolCallResult.completed(result.toString())
                .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Create agent draft failed: " + e.getMessage(), e)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @SuppressWarnings("unchecked")
    private static class Builder extends ToolCall.Builder<Builder, CreateAgentDraftTool> {
        private final CreateAgentDraftTool tool;

        Builder(CreateAgentDraftTool tool) {
            this.tool = tool;
        }

        @Override
        protected Builder self() {
            return this;
        }

        void build() {
            name(TOOL_NAME);
            description("Create a new agent definition as a draft. The agent will be in DRAFT status and can be published later with publish_agent_draft.");
            parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "name", "Name of the agent").required(),
                ToolCallParameters.ParamSpec.of(String.class, "description", "What this agent does").required(),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt", "The system prompt that defines the agent's behavior").required(),
                ToolCallParameters.ParamSpec.of(String.class, "system_prompt_id", "System prompt template ID (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "model", "LLM model to use (optional)"),
                ToolCallParameters.ParamSpec.of(Double.class, "temperature", "Temperature 0-1 (optional)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "max_turns", "Maximum conversation turns (optional, default 200)"),
                ToolCallParameters.ParamSpec.of(Integer.class, "timeout_seconds", "Timeout in seconds (optional, default 600)"),
                ToolCallParameters.ParamSpec.of(String.class, "input_template", "Input template with {{variable}} placeholders (optional)"),
                ToolCallParameters.ParamSpec.of(String.class, "multi_modal_model", "Multimodal model for image understanding (optional, e.g. 'gpt-4o')"),
                ToolCallParameters.ParamSpec.of(List.class, "tool_ids", "List of builtin tool IDs to assign to the agent (optional), e.g. [\"builtin-all\"]"),
                // New fields
                ToolCallParameters.ParamSpec.of(List.class, "subagent_ids", "List of sub-agent IDs to assign to the agent (optional)"),
                ToolCallParameters.ParamSpec.of(List.class, "skill_ids", "List of skill IDs to assign to the agent (optional)"),
                ToolCallParameters.ParamSpec.of(Map.class, "sandbox_config", "Sandbox configuration object (optional). Fields: enabled (Boolean), image (String), memory_limit_mb (Integer), cpu_limit_millicores (Integer), timeout_seconds (Integer), network_enabled (Boolean), git_repo_url (String), git_branch (String), tmp_size_limit (String), max_async_tasks (Integer), env_vars (Map<String,String>)"),
                ToolCallParameters.ParamSpec.of(List.class, "dataset_config", "List of dataset configuration objects (optional). Each item: {dataset_id: String, permission: 'READ'|'WRITE'|'FULL', is_output: Boolean}"),
                ToolCallParameters.ParamSpec.of(Map.class, "variables", "Input variable mappings as key-value pairs (optional, Map<String,String>)"),
                ToolCallParameters.ParamSpec.of(Boolean.class, "enable_memory", "Enable agent memory (optional, default false)"),
                ToolCallParameters.ParamSpec.of(String.class, "response_schema", "Response JSON Schema for structured output (optional)")
            ));
            build(tool);
        }
    }
}
