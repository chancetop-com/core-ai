package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.media.MediaProvider;
import ai.core.sandbox.Sandbox;
import ai.core.telemetry.AgentTracer;
import ai.core.server.artifact.AgentRunArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.dataset.tool.DatasetToolProvider;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TokenUsage;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.TranscriptEntry;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.SearchMemoryTool;
import ai.core.server.sandbox.SandboxLifecycle;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.skill.SkillToolAssembler;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.tools.InternalUrlResolver;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds agents for run execution. Extracted from AgentRunner to keep the file under the 450-line limit.
 *
 * @author stephen
 */
public class AgentRunBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunBuilder.class);
    private static final int MAX_TRANSCRIPT_RESULT_LENGTH = 10240;

    static String safeNodeName(AgentDefinition definition) {
        var name = definition.name != null && !definition.name.isBlank() ? definition.name : "agent-" + definition.id;
        return name.trim().replaceAll("[\\s<|\\\\/>]+", "-");
    }

    static String appendSopPriorityDeclaration(String systemPrompt) {
        var declaration = """

                ## Behavior Rules

                1. The current Skill SOP is your only behavior specification. Follow the SOP step order
                   exactly — do NOT skip, merge, or modify any step.
                2. Historical patterns in Memory are supplementary reference only. Use the search_memory
                   tool to look them up when helpful, but NEVER substitute them for SOP steps.
                3. When SOP and Memory conflict, SOP ALWAYS takes priority.
                """;
        var text = systemPrompt != null ? systemPrompt : "";
        return declaration + text;
    }

    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    SkillToolAssembler skillToolAssembler;
    @Inject
    SubAgentAssembler subAgentAssembler;
    @Inject
    SystemPromptService systemPromptService;
    @Inject
    AgentMemoryService agentMemoryService;
    @Inject
    AgentMemoryExperimentService memoryExperimentService;
    @Inject
    FileService fileService;
    @Inject
    PublicUrlConfiguration publicUrlConfiguration;
    @Inject
    DatasetService datasetService;
    @Inject
    DatasetRecordService datasetRecordService;
    @Inject
    LLMProviders llmProviders;
    @Inject
    MediaProvider mediaProvider;
    @Inject
    SystemSettingsService systemSettingsService;
    @Inject
    LLMCallExecutor llmCallExecutor;
    @Inject
    AgentTracer agentTracer;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    Agent buildAgent(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables) {
        var config = definition.publishedConfig;
        var registry = resolveToolRegistry(config, definition, runEntity.id);
        var context = buildExecutionContext(runEntity, definition, sandbox, variables);
        var enableMemory = config != null ? config.enableMemory : definition.enableMemory;
        var systemPrompt = resolveSystemPromptWithPriority(config, definition, enableMemory);
        var model = resolveModel(config, definition);
        var multiModalModel = resolveMultiModalModel(config, definition);
        var temperature = config != null ? config.temperature : definition.temperature;
        var maxTurns = config != null ? config.maxTurns : definition.maxTurns;
        attachSkillsAndSubAgents(config, definition, registry, runEntity.id);
        attachMemorySearch(registry, enableMemory, definition.id);
        var builder = createBaseBuilder(definition, registry, context);
        if (systemPrompt != null) builder.systemPrompt(systemPrompt);
        if (model != null) builder.model(model);
        if (multiModalModel != null) {
            builder.multiModalModel(multiModalModel);
        } else if (model == null) {
            var mmModel = llmProviders.getProvider().config.getMultiModalModel();
            if (mmModel != null) builder.multiModalModel(mmModel);
        }
        if (temperature != null) builder.temperature(temperature);
        if (maxTurns != null) builder.maxTurn(maxTurns);
        injectDatasetSystemVars(builder, definition);
        if (AgentMemoryService.memoryEnabled(enableMemory)) {
            attachMemoryExperiment(builder, definition, runEntity.id);
        }
        if (sandbox != null) {
            builder.addAgentLifecycle(new SandboxLifecycle(fileService,
                    new AgentRunArtifactSink(runEntity.id, agentRunCollection), publicUrlConfiguration));
        }
        var agent = builder.build();
        agent.setAuthenticated(true);
        return agent;
    }

    private ExecutionContext buildExecutionContext(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables) {
        var context = ExecutionContext.builder()
                .sessionId("run:" + runEntity.id)
                .userId(definition.userId)
                .customVariables(variables)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, publicUrlConfiguration.value()))
                .build();
        if (sandbox != null) context.sandbox(sandbox);
        if (mediaProvider != null) context.setMediaProvider(mediaProvider);
        return context;
    }

    private String resolveModel(AgentPublishedConfig config, AgentDefinition definition) {
        var model = config != null ? config.model : definition.model;
        if (model == null) model = systemSettingsService.llmModel();
        return model;
    }

    private String resolveMultiModalModel(AgentPublishedConfig config, AgentDefinition definition) {
        var mmm = config != null ? config.multiModalModel : definition.multiModalModel;
        if (mmm == null) mmm = systemSettingsService.llmMultiModalModel();
        return mmm;
    }

    private void attachSkillsAndSubAgents(AgentPublishedConfig config, AgentDefinition definition, ToolRegistry registry, String runId) {
        skillToolAssembler.attach(config != null ? config.skillIds : definition.skillIds, registry);
        var subAgents = subAgentAssembler.assemble(config != null ? config.subAgentIds : definition.subAgentIds, runId);
        if (!subAgents.isEmpty()) {
            registry.registerProvider(ListToolProvider.of("sub-agents", new ArrayList<>(subAgents)));
        }
    }

    private void attachMemorySearch(ToolRegistry registry, Boolean enableMemory, String agentId) {
        if (!AgentMemoryService.memoryEnabled(enableMemory)) return;
        var searchTool = new SearchMemoryTool(agentId, agentMemoryService);
        registry.registerProvider(ListToolProvider.of("search-memory", List.of(searchTool)));
    }

    private void attachMemoryExperiment(AgentBuilder builder, AgentDefinition definition, String runId) {
        var injectionResult = memoryExperimentService.prepareInjection(definition.id);
        if (injectionResult.injected && injectionResult.promptInject != null) {
            builder.systemPromptSection(injectionResult.promptInject);
        }
        var experimentConfig = memoryExperimentService.getConfig(definition.id);
        if (experimentConfig != null) {
            memoryExperimentService.startRun(definition.id, "run:" + runId, runId, experimentConfig, injectionResult);
        }
    }

    void updateRunStatus(AgentRun runEntity, RunStatus status, String output, String error, Agent agent) {
        var latestRun = agentRunCollection.get(runEntity.id).orElse(runEntity);
        runEntity.artifacts = latestRun.artifacts;
        if ((runEntity.traceId == null || runEntity.traceId.isBlank()) && latestRun.traceId != null && !latestRun.traceId.isBlank()) {
            runEntity.traceId = latestRun.traceId;
        }
        runEntity.status = status;
        runEntity.output = output;
        runEntity.error = error;
        runEntity.completedAt = ZonedDateTime.now();

        if (agent != null) {
            var usage = agent.getCurrentTokenUsage();
            var tokenUsage = new TokenUsage();
            tokenUsage.input = (long) usage.getPromptTokens();
            tokenUsage.output = (long) usage.getCompletionTokens();
            runEntity.tokenUsage = tokenUsage;

            runEntity.transcript = buildTranscript(agent);
        }

        agentRunCollection.replace(runEntity);
    }

    List<TranscriptEntry> buildTranscript(Agent agent) {
        var transcript = new ArrayList<TranscriptEntry>();
        for (var message : agent.getMessages()) {
            var entry = new TranscriptEntry();
            entry.timestamp = ZonedDateTime.now();
            entry.role = message.role != null ? message.role.name().toLowerCase(Locale.ROOT) : "unknown";
            var content = message.content != null && !message.content.isEmpty()
                    ? message.content.getFirst().text : null;
            if (content != null && content.length() > MAX_TRANSCRIPT_RESULT_LENGTH) {
                content = content.substring(0, MAX_TRANSCRIPT_RESULT_LENGTH) + "...(truncated)";
            }
            entry.content = content;
            transcript.add(entry);
        }
        return transcript;
    }

    List<TranscriptEntry> buildLLMCallTranscript(String systemPrompt, String input, String output) {
        var transcript = new ArrayList<TranscriptEntry>();
        if (systemPrompt != null) {
            var systemEntry = new TranscriptEntry();
            systemEntry.timestamp = ZonedDateTime.now();
            systemEntry.role = "system";
            systemEntry.content = systemPrompt;
            transcript.add(systemEntry);
        }
        var userEntry = new TranscriptEntry();
        userEntry.timestamp = ZonedDateTime.now();
        userEntry.role = "user";
        userEntry.content = input;
        transcript.add(userEntry);

        var assistantEntry = new TranscriptEntry();
        assistantEntry.timestamp = ZonedDateTime.now();
        assistantEntry.role = "assistant";
        assistantEntry.content = output;
        transcript.add(assistantEntry);
        return transcript;
    }

    void extractDatasetRecords(String output, AgentDefinition definition, String runId, String agentId, ZonedDateTime runStartedAt) {
        if (output == null || output.isBlank()) return;
        var outputDatasetId = AgentDefinitionService.resolveOutputDatasetId(definition);
        if (outputDatasetId == null) return;
        try {
            var dataset = datasetService.get(outputDatasetId);
            if (dataset == null) {
                LOGGER.warn("output dataset not found, datasetId={}, runId={}", outputDatasetId, runId);
                return;
            }
            var data = llmCallExecutor.extractStructured(output, dataset, definition);
            if (data != null && !data.isEmpty()) {
                datasetRecordService.insert(new DatasetRecordService.InsertRequest(
                        dataset.id, agentId, runId, runStartedAt, data, definition.userId, definition.userId));
            }
        } catch (Exception e) {
            LOGGER.warn("failed to extract dataset record, datasetId={}, runId={}", outputDatasetId, runId, e);
        }
    }

    private String resolveSystemPrompt(AgentPublishedConfig config, AgentDefinition definition) {
        var promptId = config != null ? config.systemPromptId : definition.systemPromptId;
        if (promptId != null && !promptId.isBlank()) {
            return systemPromptService.resolveContent(promptId);
        }
        return config != null ? config.systemPrompt : definition.systemPrompt;
    }

    private void addDatasetTools(ToolRegistry registry, AgentDefinition definition, String runId) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var accessRegistry = DatasetAccessRegistry.from(datasetConfig, datasetService);
        registry.registerProvider(new DatasetToolProvider(datasetService, datasetRecordService, accessRegistry, definition.id, runId));
    }

    private String appendDatasetInstructions(String systemPrompt, AgentDefinition definition) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return systemPrompt;
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    private void injectDatasetSystemVars(AgentBuilder builder, AgentDefinition definition) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var names = new ArrayList<String>();
        var desc = new StringBuilder();
        for (var cfg : datasetConfig) {
            var dataset = datasetService.get(cfg.datasetId);
            if (dataset == null) continue;
            names.add(dataset.name);
            desc.append("\n- \"").append(dataset.name).append("\" (").append(cfg.permission.name()).append(')');
            if (dataset.description != null && !dataset.description.isBlank()) {
                desc.append(": ").append(dataset.description);
            }
        }
        builder.extraSystemVariable(SystemVariables.AGENT_DATASET_NAME, String.join(", ", names));
        builder.extraSystemVariable(SystemVariables.AGENT_DATASET_DESC, desc.toString());
    }

    private ToolRegistry resolveToolRegistry(AgentPublishedConfig config, AgentDefinition definition, String runId) {
        List<ToolRef> toolRefs;
        if (config != null && config.tools != null && !config.tools.isEmpty()) {
            toolRefs = config.tools;
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            toolRefs = definition.tools;
        } else {
            toolRefs = List.of();
        }
        var registry = toolRegistryService.resolveToToolRegistry(toolRefs, runId);
        addDatasetTools(registry, definition, runId);
        return registry;
    }

    private String resolveSystemPromptWithPriority(AgentPublishedConfig config, AgentDefinition definition, Boolean enableMemory) {
        var systemPrompt = resolveSystemPrompt(config, definition);
        systemPrompt = appendDatasetInstructions(systemPrompt, definition);
        if (AgentMemoryService.memoryEnabled(enableMemory)) {
            systemPrompt = appendSopPriorityDeclaration(systemPrompt);
        }
        return systemPrompt;
    }

    private AgentBuilder createBaseBuilder(AgentDefinition definition, ToolRegistry registry, ExecutionContext context) {
        return Agent.builder()
                .name(safeNodeName(definition))
                .id(definition.id)
                .description(definition.description != null ? definition.description : definition.name)
                .llmProvider(llmProviders.getProvider())
                .toolRegistry(registry)
                .executionContext(context)
                .tracer(agentTracer);
    }
}
