package ai.core.server.session;

import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.dataset.tool.DatasetToolProvider;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.DatasetPermission;
import ai.core.api.server.session.SessionConfig;
import ai.core.tool.registry.BuiltinToolProvider;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared dataset assembly for every agent build path (top-level session agent, session rebuild, run agent,
 * sub-agent). All paths must wire dataset tools, dataset instructions and dataset system variables through
 * this single helper so no path can silently miss dataset support.
 *
 * @author Lim Chen
 */
public class SessionDatasetHelper {
    private final DatasetService datasetService;
    private final DatasetRecordService datasetRecordService;

    public SessionDatasetHelper(DatasetService datasetService, DatasetRecordService datasetRecordService) {
        this.datasetService = datasetService;
        this.datasetRecordService = datasetRecordService;
    }

    /**
     * The session-level dataset shortcut: a single dataset id on the session config becomes a
     * read-only dataset binding.
     */
    public List<AgentDatasetConfig> sessionDatasetConfig(SessionConfig config) {
        if (config.datasetId == null || config.datasetId.isBlank()) return null;
        var dp = new AgentDatasetConfig();
        dp.datasetId = config.datasetId;
        dp.permission = DatasetPermission.READ;
        return List.of(dp);
    }

    /** Dataset instructions are appended to the config's system prompt as a side effect. */
    public Map<String, Object> buildExtraVars(SessionConfig config, List<AgentDatasetConfig> datasetConfig) {
        Map<String, Object> extraVars = null;
        if (datasetConfig != null && !datasetConfig.isEmpty()) {
            config.systemPrompt = appendDatasetInstructions(config.systemPrompt, datasetConfig);
            extraVars = buildDatasetSystemVars(datasetConfig);
        }
        if (config.channelType != null && !config.channelType.isBlank()) {
            if (extraVars == null) extraVars = new HashMap<>();
            extraVars.put("system.channel.type", config.channelType);
        }
        return extraVars;
    }

    public void addDatasetToolsToRegistry(ToolRegistry registry, List<AgentDatasetConfig> datasetConfig, String agentId, String sessionId) {
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var accessRegistry = DatasetAccessRegistry.from(datasetConfig, datasetService);
        registry.registerProvider(new DatasetToolProvider(datasetService, datasetRecordService, accessRegistry, agentId, sessionId));
    }

    public String appendDatasetInstructions(String systemPrompt, List<AgentDatasetConfig> datasetConfig) {
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    public Map<String, Object> buildDatasetSystemVars(List<AgentDatasetConfig> datasetConfig) {
        if (datasetConfig == null || datasetConfig.isEmpty()) return null;
        var first = datasetConfig.getFirst();
        var dataset = datasetService.get(first.datasetId);
        if (dataset == null) return null;
        var vars = new HashMap<String, Object>();
        vars.put(SystemVariables.AGENT_DATASET_NAME, buildDatasetNames(datasetConfig));
        vars.put(SystemVariables.AGENT_DATASET_DESC, buildDatasetDesc(datasetConfig));
        return vars;
    }

    private String buildDatasetNames(List<AgentDatasetConfig> datasetConfig) {
        var names = new ArrayList<String>();
        for (var perm : datasetConfig) {
            var dataset = datasetService.get(perm.datasetId);
            if (dataset != null) names.add(dataset.name);
        }
        return String.join(", ", names);
    }

    private String buildDatasetDesc(List<AgentDatasetConfig> datasetConfig) {
        var sb = new StringBuilder();
        for (var perm : datasetConfig) {
            var dataset = datasetService.get(perm.datasetId);
            if (dataset == null) continue;
            sb.append("\n- \"").append(dataset.name).append("\" (").append(perm.permission.name()).append(')');
            if (dataset.description != null && !dataset.description.isBlank()) {
                sb.append(": ").append(dataset.description);
            }
        }
        return sb.toString();
    }

    ToolRegistry buildSessionToolRegistry(SessionConfig config, String sessionId, ai.core.schedule.ScheduledTaskStore scheduledTaskStore) {
        var registry = ToolRegistryFactory.createEmpty();
        registry.registerProvider(builtinAllProvider(scheduledTaskStore));
        if (config == null || !hasText(config.datasetId)) return registry;
        var dataset = datasetService.get(config.datasetId);
        if (dataset == null) return registry;
        var dp = new AgentDatasetConfig();
        dp.datasetId = config.datasetId;
        dp.permission = DatasetPermission.FULL;
        var accessRegistry = DatasetAccessRegistry.from(List.of(dp), datasetService);
        registry.registerProvider(new DatasetToolProvider(datasetService, datasetRecordService, accessRegistry, "default", sessionId));
        return registry;
    }

    private ToolProvider builtinAllProvider(ai.core.schedule.ScheduledTaskStore scheduledTaskStore) {
        if (scheduledTaskStore == null) return BuiltinToolProvider.fromSet(ToolProvider.BUILTIN_ALL);
        // the builtin-all placeholder carries no store; swap in the store-bound tool
        return BuiltinToolProvider.fromSet(ToolProvider.BUILTIN_ALL, null, null, null,
                tools -> tools.stream()
                        .map(tool -> tool instanceof ai.core.tool.tools.ScheduledTaskTool
                                ? ai.core.tool.tools.ScheduledTaskTool.builder(scheduledTaskStore).build()
                                : tool)
                        .toList());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
