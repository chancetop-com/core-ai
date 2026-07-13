package ai.core.server.agent;

import ai.core.api.server.agent.AgentDatasetConfigView;
import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.api.server.agent.SandboxConfigView;
import ai.core.api.server.session.IdName;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.util.IdLists;

import java.util.List;
import java.util.Map;

/**
 * Static view conversion helpers extracted from {@link AgentDefinitionService}
 * to keep file length under the checkstyle limit.
 *
 * @author stephen
 */
final class AgentViewHelper {

    static AgentDefinitionView buildView(AgentDefinition entity, Map<String, String> subAgentNameMap, Map<String, String> skillNameMap) {
        var view = new AgentDefinitionView();
        view.id = entity.id;
        view.name = entity.name;
        view.description = entity.description;
        view.systemPrompt = entity.systemPrompt;
        view.systemPromptId = entity.systemPromptId;
        view.model = entity.model;
        view.multiModalModel = entity.multiModalModel;
        view.temperature = entity.temperature;
        view.maxTurns = entity.maxTurns;
        view.timeoutSeconds = entity.timeoutSeconds;
        view.tools = toToolRefViews(entity.tools);
        view.inputTemplate = entity.inputTemplate;
        view.variables = entity.variables;
        view.systemDefault = entity.systemDefault;
        view.enableMemory = entity.enableMemory;
        view.type = entity.type != null ? entity.type.name() : DefinitionType.AGENT.name();
        view.responseSchema = entity.responseSchema;
        view.subAgentIds = IdLists.cleanOrNull(entity.subAgentIds);
        view.skillIds = IdLists.cleanOrNull(entity.skillIds);
        view.subAgents = view.subAgentIds != null ? view.subAgentIds.stream()
                .map(id -> {
                    var v = new IdName();
                    v.id = id;
                    v.name = subAgentNameMap.getOrDefault(id, id);
                    return v;
                })
                .toList() : null;
        view.skills = view.skillIds != null ? view.skillIds.stream()
                .map(id -> {
                    var v = new IdName();
                    v.id = id;
                    v.name = skillNameMap.getOrDefault(id, id);
                    return v;
                })
                .toList() : null;
        view.status = entity.status != null ? entity.status.name() : null;
        view.publishedAt = entity.publishedAt;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        view.sandboxConfig = toSandboxConfigView(entity.sandboxConfig);
        view.datasetConfig = toDatasetConfigViews(entity.datasetConfig);
        return view;
    }

    static List<ToolRef> toToolRefs(List<ToolRefView> views) {
        if (views == null || views.isEmpty()) {
            return null;
        }
        return views.stream().map(v -> {
            var ref = new ToolRef();
            ref.id = v.id;
            ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
            ref.source = v.source;
            if (ref.type == null) ref.inferTypeFromId();
            return ref;
        }).toList();
    }

    static List<ToolRefView> toToolRefViews(List<ToolRef> refs) {
        if (refs == null) return null;
        return refs.stream().map(ref -> {
            var view = new ToolRefView();
            view.id = ref.id;
            view.type = ref.type != null ? ref.type.name() : null;
            view.source = ref.source;
            return view;
        }).toList();
    }

    static SandboxConfigView toSandboxConfigView(AgentSandboxConfig config) {
        if (config == null) return null;
        var view = new SandboxConfigView();
        view.enabled = config.enabled;
        view.image = config.image;
        view.memoryLimitMb = config.memoryLimitMb;
        view.cpuLimitMillicores = config.cpuLimitMillicores;
        view.timeoutSeconds = config.timeoutSeconds;
        view.networkEnabled = config.networkEnabled;
        view.gitRepoUrl = config.gitRepoUrl;
        view.gitBranch = config.gitBranch;
        view.tmpSizeLimit = config.tmpSizeLimit;
        view.maxAsyncTasks = config.maxAsyncTasks;
        view.envVars = config.environmentVariables;
        return view;
    }

    static AgentSandboxConfig fromSandboxConfigView(SandboxConfigView view) {
        if (view == null) return null;
        var config = new AgentSandboxConfig();
        config.enabled = view.enabled;
        config.image = view.image;
        config.memoryLimitMb = view.memoryLimitMb;
        config.cpuLimitMillicores = view.cpuLimitMillicores;
        config.timeoutSeconds = view.timeoutSeconds;
        config.networkEnabled = view.networkEnabled;
        config.gitRepoUrl = view.gitRepoUrl;
        config.gitBranch = view.gitBranch;
        config.tmpSizeLimit = view.tmpSizeLimit;
        config.maxAsyncTasks = view.maxAsyncTasks;
        config.environmentVariables = view.envVars;
        return config;
    }

    static List<AgentDatasetConfig> toDatasetConfigs(List<AgentDatasetConfigView> views) {
        if (views == null || views.isEmpty()) return null;
        return views.stream().map(v -> {
            var config = new AgentDatasetConfig();
            config.datasetId = v.datasetId;
            config.permission = v.permission != null ? DatasetPermission.valueOf(v.permission) : DatasetPermission.READ;
            config.isOutput = v.isOutput;
            return config;
        }).toList();
    }

    static List<AgentDatasetConfigView> toDatasetConfigViews(List<AgentDatasetConfig> configs) {
        if (configs == null) return null;
        return configs.stream().map(c -> {
            var view = new AgentDatasetConfigView();
            view.datasetId = c.datasetId;
            view.permission = c.permission.name();
            view.isOutput = c.isOutput;
            return view;
        }).toList();
    }

    static String resolveOutputDatasetId(AgentDefinition definition) {
        var configs = resolveDatasetConfig(definition);
        if (configs == null) return null;
        return configs.stream()
                .filter(c -> c.isOutput != null && c.isOutput)
                .findFirst()
                .map(c -> c.datasetId)
                .orElse(null);
    }

    static List<AgentDatasetConfig> resolveDatasetConfig(AgentDefinition definition) {
        var config = definition.publishedConfig;
        if (config != null && config.datasetConfig != null && !config.datasetConfig.isEmpty()) {
            return config.datasetConfig;
        }
        if (definition.datasetConfig != null && !definition.datasetConfig.isEmpty()) {
            return definition.datasetConfig;
        }
        return null;
    }
}
