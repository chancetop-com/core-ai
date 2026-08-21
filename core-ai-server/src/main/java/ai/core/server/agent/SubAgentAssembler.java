package ai.core.server.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.persistence.PersistenceProviders;
import ai.core.telemetry.AgentTracer;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.session.SessionDatasetHelper;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.skill.SkillToolAssembler;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.prompt.PromptInject;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.tool.tools.SubAgentToolCall;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds sub-agents (tools + skills included) from agent definitions.
 * Shared by the run path (AgentRunner) and the session path (SessionSubAgentManager) so both assemble
 * sub-agents identically — in particular both wire skills, which previously only the top-level agent did.
 *
 * @author Xander
 */
public class SubAgentAssembler {
    private static String normalizeThinkingEffort(String value) {
        var effort = ReasoningEffort.fromString(value);
        return effort != null ? effort.name().toLowerCase(Locale.ROOT) : null;
    }

    private final Logger logger = LoggerFactory.getLogger(SubAgentAssembler.class);

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    SystemPromptService systemPromptService;
    @Inject
    LLMProviders llmProviders;
    @Inject
    PersistenceProviders persistenceProviders;
    @Inject
    SkillToolAssembler skillToolAssembler;
    @Inject
    SystemSettingsService systemSettingsService;
    @Inject
    AgentTracer agentTracer;
    @Inject
    DatasetService datasetService;
    @Inject
    DatasetRecordService datasetRecordService;

    private SessionDatasetHelper datasetHelper;

    private SessionDatasetHelper datasetHelper() {
        if (datasetHelper == null) datasetHelper = new SessionDatasetHelper(datasetService, datasetRecordService);
        return datasetHelper;
    }

    /**
     * Loads sub-agent definitions by id and wraps each as a callable tool.
     * A missing or broken sub-agent is logged and skipped so it never blocks the parent agent from starting.
     */
    public List<SubAgentToolCall> assemble(List<String> subAgentIds, String sessionId) {
        return assemble(subAgentIds, sessionId, null);
    }

    public List<SubAgentToolCall> assemble(List<String> subAgentIds, String sessionId, String callerUserId) {
        var ids = IdLists.clean(subAgentIds);
        if (ids.isEmpty()) return List.of();
        var tools = new ArrayList<SubAgentToolCall>();
        for (var id : ids) {
            try {
                var definition = agentDefinitionCollection.get(id)
                        .orElseThrow(() -> new RuntimeException("subagent not found, id=" + id));
                var subAgent = buildSubAgent(definition, sessionId, callerUserId);
                tools.add(SubAgentToolCall.builder().subAgent(subAgent).build());
                logger.info("assembled subagent {} (id={})", definition.name, id);
            } catch (Exception e) {
                logger.warn("failed to assemble subagent id={}: {}", id, e.getMessage());
            }
        }
        return tools;
    }

    public Agent buildSubAgent(AgentDefinition definition, String sessionId) {
        return buildSubAgent(definition, sessionId, null);
    }

    public Agent buildSubAgent(AgentDefinition definition, String sessionId, String callerUserId) {
        requireUsablePublishedSubAgent(definition);
        var config = toSessionConfig(definition);
        var toolRegistry = resolveToolsToRegistry(definition, sessionId, callerUserId);
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        Map<String, Object> extraVars = null;
        if (datasetConfig != null && !datasetConfig.isEmpty()) {
            datasetHelper().addDatasetToolsToRegistry(toolRegistry, datasetConfig, definition.id, sessionId);
            config.systemPrompt = datasetHelper().appendDatasetInstructions(config.systemPrompt, datasetConfig);
            extraVars = datasetHelper().buildDatasetSystemVars(datasetConfig);
        }
        skillToolAssembler.attach(resolveSkillIds(definition), toolRegistry);
        var bc = new BuildAgentConfig(config, toolRegistry, null, definition.name, extraVars, definition.id, null, null, null);
        return buildAgent(bc);
    }

    private List<String> resolveSkillIds(AgentDefinition definition) {
        var source = definition.publishedConfig;
        return source != null ? source.skillIds : definition.skillIds;
    }

    public ToolRegistry resolveToolsToRegistry(AgentDefinition definition, String sessionId) {
        return resolveToolsToRegistry(definition, sessionId, null);
    }

    public ToolRegistry resolveToolsToRegistry(AgentDefinition definition, String sessionId, String callerUserId) {
        requireUsablePublishedSubAgent(definition);
        return resolveConfiguredToolsToRegistry(definition.publishedConfig.tools, sessionId, callerUserId);
    }

    public ToolRegistry resolveTopLevelToolsToRegistry(AgentDefinition definition, String sessionId,
                                                       String callerUserId) {
        var source = definition.publishedConfig;
        var tools = source != null ? source.tools : definition.tools;
        return resolveConfiguredToolsToRegistry(tools, sessionId, callerUserId);
    }

    private ToolRegistry resolveConfiguredToolsToRegistry(List<ToolRef> tools,
                                                          String sessionId, String callerUserId) {
        return tools != null && !tools.isEmpty()
            ? toolRegistryService.resolveToToolRegistry(tools, sessionId, callerUserId)
            : ToolRegistryFactory.createEmpty();
    }

    public List<ToolCall> resolveTools(AgentDefinition definition, String sessionId) {
        return resolveTools(definition, sessionId, null);
    }

    public List<ToolCall> resolveTools(AgentDefinition definition, String sessionId, String callerUserId) {
        requireUsablePublishedSubAgent(definition);
        var tools = definition.publishedConfig.tools;
        return tools != null && !tools.isEmpty()
            ? toolRegistryService.resolveToolRefs(tools, sessionId, callerUserId)
            : List.of();
    }

    public SessionConfig toSessionConfig(AgentDefinition definition) {
        var config = new SessionConfig();
        var source = definition.publishedConfig;
        var hasSource = source != null;
        var systemPromptId = hasSource ? source.systemPromptId : definition.systemPromptId;
        var inlineSystemPrompt = hasSource ? source.systemPrompt : definition.systemPrompt;
        config.systemPrompt = systemPromptId != null ? systemPromptService.resolveContent(systemPromptId) : inlineSystemPrompt;
        config.model = hasSource ? source.model : definition.model;
        config.multiModalModel = hasSource ? source.multiModalModel : definition.multiModalModel;
        config.preferCaptionPath = hasSource ? source.preferCaptionPath : definition.preferCaptionPath;
        config.temperature = hasSource ? source.temperature : definition.temperature;
        config.reasoningEffort = normalizeThinkingEffort(hasSource ? source.thinkingEffort : definition.thinkingEffort);
        config.maxTurns = hasSource ? source.maxTurns : definition.maxTurns;
        return config;
    }

    private void requireUsablePublishedSubAgent(AgentDefinition definition) {
        if (!AgentDependencyAccessPolicy.hasUsablePublishedSubAgent(definition)) {
            throw new IllegalArgumentException("sub-agent is unavailable");
        }
    }

    public Agent buildAgent(BuildAgentConfig c) {
        var llmProvider = llmProviders.getProvider();
        var builder = Agent.builder()
                .name(c.agentName != null && !c.agentName.isBlank() ? c.agentName.trim().replaceAll("[\\s<|\\\\/>]+", "-") : "assistant")
                .llmProvider(llmProvider)
                .toolRegistry(c.toolRegistry)
                .temperature(c.config != null && c.config.temperature != null ? c.config.temperature : 0.8)
                .tracer(agentTracer);
        if (c.agentId != null && !c.agentId.isBlank()) {
            builder.id(c.agentId);
        }
        if (c.config != null) {
            if (c.config.systemPrompt != null) {
                builder.systemPrompt(c.config.systemPrompt);
            } else {
                builder.systemPrompt("You are a helpful AI assistant.");
            }
            if (c.config.model != null) builder.model(c.config.model);
            configureMultiModalModel(builder, c.config, llmProvider);
            if (Boolean.TRUE.equals(c.config.preferCaptionPath)) builder.preferCaptionPath(true);
            if (c.config.reasoningEffort != null) builder.reasoningEffort(ReasoningEffort.fromString(c.config.reasoningEffort));
            if (c.config.maxTurns != null) builder.maxTurn(c.config.maxTurns);
        } else {
            builder.systemPrompt("You are a helpful AI assistant.");
            var mmModel = resolveMultiModalModel(null, llmProvider);
            if (mmModel != null) builder.multiModalModel(mmModel);
        }
        if (c.context != null) builder.executionContext(c.context);
        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) builder.persistenceProvider(provider);
        if (c.extraSystemVars != null) {
            c.extraSystemVars.forEach(builder::extraSystemVariable);
        }
        if (c.extraLifecycles != null && !c.extraLifecycles.isEmpty()) {
            builder.agentLifecycle(c.extraLifecycles);
        }
        if (c.memoryInject != null) {
            builder.systemPromptSection(c.memoryInject);
        }
        if (c.channelInject != null) {
            builder.systemPromptSection(c.channelInject);
        }
        return builder.build();
    }

    private void configureMultiModalModel(AgentBuilder builder, SessionConfig config, LLMProvider llmProvider) {
        var mmModel = resolveMultiModalModel(config, llmProvider);
        if (mmModel != null) builder.multiModalModel(mmModel);
    }

    // mirrors AgentRunBuilder: a pinned text-only model still needs a vision fallback when images appear,
    // otherwise the upstream rejects the request with 400
    private String resolveMultiModalModel(SessionConfig config, LLMProvider llmProvider) {
        if (config != null && config.multiModalModel != null) return config.multiModalModel;
        var mmModel = systemSettingsService.captionImageModel();
        return mmModel != null ? mmModel : llmProvider.config.getMultiModalModel();
    }

    public record BuildAgentConfig(SessionConfig config, ToolRegistry toolRegistry, ExecutionContext context,
                                    String agentName, Map<String, Object> extraSystemVars, String agentId,
                                    List<AbstractLifecycle> extraLifecycles, PromptInject memoryInject,
                                    PromptInject channelInject) {
    }
}
