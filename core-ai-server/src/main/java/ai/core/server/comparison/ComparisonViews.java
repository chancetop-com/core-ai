package ai.core.server.comparison;

import ai.core.api.server.agent.AgentDatasetConfigView;
import ai.core.api.server.agent.SandboxConfigView;
import ai.core.api.server.comparison.ComparisonVariantConfigView;
import ai.core.api.server.tool.ToolRefView;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.util.IdLists;

import java.util.List;
import java.util.Locale;

/**
 * Conversions between {@link AgentPublishedConfig} and its API view, used by the comparison
 * management surface (mirrors {@code AgentViewHelper} conventions).
 *
 * @author stephen
 */
public final class ComparisonViews {
    public static ComparisonVariantConfigView toConfigView(AgentPublishedConfig config) {
        if (config == null) return null;
        var view = new ComparisonVariantConfigView();
        view.systemPrompt = config.systemPrompt;
        view.systemPromptId = config.systemPromptId;
        view.model = config.model;
        view.multiModalModel = config.multiModalModel;
        view.preferCaptionPath = config.preferCaptionPath;
        view.temperature = config.temperature;
        view.thinkingEffort = config.thinkingEffort;
        view.maxTurns = config.maxTurns;
        view.timeoutSeconds = config.timeoutSeconds;
        view.tools = toToolRefViews(config.tools);
        view.skillIds = IdLists.cleanOrNull(config.skillIds);
        view.subAgentIds = IdLists.cleanOrNull(config.subAgentIds);
        view.inputTemplate = config.inputTemplate;
        view.variables = config.variables;
        view.responseSchema = config.responseSchema;
        view.enableMemory = config.enableMemory;
        view.sandboxConfig = toSandboxConfigView(config.sandboxConfig);
        view.datasetConfig = toDatasetConfigViews(config.datasetConfig);
        return view;
    }

    /** Patch semantics: a null field keeps the current value (empty tools / skill lists clear them). */
    public static void applyConfigPatch(AgentPublishedConfig config, ComparisonVariantConfigView patch) {
        if (patch.systemPrompt != null) config.systemPrompt = patch.systemPrompt;
        if (patch.systemPromptId != null) config.systemPromptId = patch.systemPromptId.isBlank() ? null : patch.systemPromptId;
        if (patch.model != null) config.model = patch.model;
        if (patch.multiModalModel != null) config.multiModalModel = patch.multiModalModel.isBlank() ? null : patch.multiModalModel;
        if (patch.preferCaptionPath != null) config.preferCaptionPath = patch.preferCaptionPath;
        if (patch.temperature != null) config.temperature = patch.temperature;
        if (patch.thinkingEffort != null) {
            var effort = ReasoningEffort.fromString(patch.thinkingEffort);
            config.thinkingEffort = effort != null ? effort.name().toLowerCase(Locale.ROOT) : null;
        }
        if (patch.maxTurns != null) config.maxTurns = patch.maxTurns;
        if (patch.timeoutSeconds != null) config.timeoutSeconds = patch.timeoutSeconds;
        if (patch.tools != null) config.tools = toToolRefs(patch.tools);
        if (patch.skillIds != null) config.skillIds = IdLists.cleanOrNull(patch.skillIds);
        if (patch.subAgentIds != null) config.subAgentIds = IdLists.cleanOrNull(patch.subAgentIds);
        if (patch.inputTemplate != null) config.inputTemplate = patch.inputTemplate;
        if (patch.variables != null) config.variables = patch.variables;
        if (patch.responseSchema != null) config.responseSchema = patch.responseSchema;
        if (patch.enableMemory != null) config.enableMemory = patch.enableMemory;
        if (patch.sandboxConfig != null) config.sandboxConfig = fromSandboxConfigView(patch.sandboxConfig);
        if (patch.datasetConfig != null) config.datasetConfig = toDatasetConfigs(patch.datasetConfig);
    }

    public static List<ToolRef> toToolRefs(List<ToolRefView> views) {
        if (views == null || views.isEmpty()) {
            return null;
        }
        return views.stream().map(view -> {
            var ref = new ToolRef();
            ref.id = view.id;
            ref.type = view.type != null ? ToolSourceType.valueOf(view.type) : null;
            ref.source = view.source;
            if (ref.type == null) ref.inferTypeFromId();
            return ref;
        }).toList();
    }

    public static List<ToolRefView> toToolRefViews(List<ToolRef> refs) {
        if (refs == null) return null;
        return refs.stream().map(ComparisonToolFreeze::toView).toList();
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
        return views.stream().map(view -> {
            var config = new AgentDatasetConfig();
            config.datasetId = view.datasetId;
            config.permission = view.permission != null ? DatasetPermission.valueOf(view.permission) : DatasetPermission.READ;
            config.isOutput = view.isOutput;
            return config;
        }).toList();
    }

    static List<AgentDatasetConfigView> toDatasetConfigViews(List<AgentDatasetConfig> configs) {
        if (configs == null) return null;
        return configs.stream().map(config -> {
            var view = new AgentDatasetConfigView();
            view.datasetId = config.datasetId;
            view.permission = config.permission != null ? config.permission.name() : null;
            view.isOutput = config.isOutput;
            return view;
        }).toList();
    }

    private ComparisonViews() {
    }
}
