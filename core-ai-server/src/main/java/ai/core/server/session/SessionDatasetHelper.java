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

final class SessionDatasetHelper {
    private final DatasetService datasetService;
    private final DatasetRecordService datasetRecordService;

    SessionDatasetHelper(DatasetService datasetService, DatasetRecordService datasetRecordService) {
        this.datasetService = datasetService;
        this.datasetRecordService = datasetRecordService;
    }

    void addDatasetToolsToRegistry(ToolRegistry registry, List<AgentDatasetConfig> datasetConfig, String agentId, String sessionId) {
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var accessRegistry = DatasetAccessRegistry.from(datasetConfig, datasetService);
        registry.registerProvider(new DatasetToolProvider(datasetService, datasetRecordService, accessRegistry, agentId, sessionId));
    }

    String appendDatasetInstructions(String systemPrompt, List<AgentDatasetConfig> datasetConfig) {
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    Map<String, Object> buildDatasetSystemVars(List<AgentDatasetConfig> datasetConfig) {
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

    ToolRegistry buildSessionToolRegistry(SessionConfig config, String sessionId) {
        var registry = ToolRegistryFactory.createEmpty();
        registry.registerProvider(BuiltinToolProvider.fromSet(ToolProvider.BUILTIN_ALL));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
