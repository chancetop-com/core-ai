package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.llm.LLMProviders;
import ai.core.sandbox.Sandbox;
import ai.core.server.artifact.AgentRunArtifactSink;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.memory.AgentMemoryService;
import ai.core.tool.ToolCall;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TokenUsage;
import ai.core.server.domain.TranscriptEntry;
import ai.core.server.domain.TriggerType;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.dataset.tool.DeleteDatasetRecordTool;
import ai.core.server.dataset.tool.InsertDatasetRecordTool;
import ai.core.server.dataset.tool.QueryDatasetRecordsTool;
import ai.core.server.dataset.tool.UpdateDatasetRecordTool;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.util.IdLists;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.server.tool.ToolRegistryService;
import ai.core.skill.SkillRegistry;
import ai.core.telemetry.TelemetryConfig;
import ai.core.tool.tools.ReadSkillResourceTool;
import ai.core.tool.tools.InternalUrlResolver;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class AgentRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunner.class);
    private static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    private static final AttributeKey<String> GEN_AI_OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> GEN_AI_AGENT_ID = AttributeKey.stringKey("gen_ai.agent.id");
    private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("gen_ai.completion");
    private static final AttributeKey<String> AGENT_STATUS = AttributeKey.stringKey("agent.status");
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");
    private static final AttributeKey<String> CLIENT_TYPE = AttributeKey.stringKey("client.type");
    private static final AttributeKey<String> CORE_AI_RUN_ID = AttributeKey.stringKey("core_ai.run_id");
    private static final AttributeKey<String> CORE_AI_SCHEDULE_ID = AttributeKey.stringKey("core_ai.schedule_id");
    private static final int MAX_CONCURRENT_RUNS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int SANDBOX_RELEASE_DELAY_SECONDS = 60;
    private static final int MAX_TRANSCRIPT_RESULT_LENGTH = 10240;
    // RUNNING records older than this are treated as ghost rows (left over from crashes / restarts)
    // and ignored by isRunning(), so SKIP-policy schedules don't get jammed forever.
    private static final int STALE_RUN_THRESHOLD_SECONDS = 1800;

    private final ExecutorService executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();

    @Inject
    LLMProviders llmProviders;

    @Inject
    LLMCallExecutor llmCallExecutor;

    @Inject
    ToolRegistryService toolRegistryService;

    @Inject
    MongoSkillProvider mongoSkillProvider;

    @Inject
    SkillService skillService;

    @Inject
    SkillArchiveBuilder skillArchiveBuilder;

    @Inject
    SystemPromptService systemPromptService;

    @Inject
    AgentMemoryService agentMemoryService;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    @Inject
    SandboxService sandboxService;

    @Inject
    FileService fileService;

    @Inject
    DatasetService datasetService;

    @Inject
    DatasetRecordService datasetRecordService;
    @Inject
    TelemetryConfig telemetryConfig;

    public String run(AgentDefinition definition, String input, TriggerType trigger) {
        return run(definition, input, trigger, null, null);
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId, Map<String, String> runtimeVariables) {
        var resolvedVariables = new HashMap<String, Object>();
        if (runtimeVariables != null) {
            resolvedVariables.putAll(runtimeVariables);
        }
        var runEntity = createRunRecord(definition, input, trigger, scheduleId);
        agentRunCollection.insert(runEntity);

        var runId = runEntity.id;

        // Create sandbox with effective config (platform default + agent override)
        var sandboxConfig = sandboxService.getEffectiveConfig(definition);

        var sandbox = sandboxService.createSandbox(sandboxConfig, runId, definition.userId);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                execute(runEntity, definition, sandbox, resolvedVariables);
            } finally {
                scheduleSandboxRelease(runId);
            }
        }, executorService);
        runningFutures.put(runId, future);
        future.whenComplete((result, error) -> runningFutures.remove(runId));

        return runId;
    }

    private void scheduleSandboxRelease(String runId) {
        timeoutScheduler.schedule(() -> {
            try {
                sandboxService.releaseSandbox(runId);
            } catch (Exception e) {
                LOGGER.warn("failed to release sandbox for runId={}", runId, e);
            }
        }, SANDBOX_RELEASE_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public void cancel(String runId) {
        var future = runningFutures.get(runId);
        if (future != null) {
            future.cancel(true);
        }
        agentRunCollection.get(runId).ifPresent(run -> {
            if (run.status == RunStatus.RUNNING) {
                run.status = RunStatus.CANCELLED;
                run.completedAt = ZonedDateTime.now();
                agentRunCollection.replace(run);
            }
        });
    }

    public boolean isRunning(String agentId, String scheduleId) {
        var threshold = ZonedDateTime.now().minusSeconds(STALE_RUN_THRESHOLD_SECONDS);
        return agentRunCollection.findOne(
            Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("schedule_id", scheduleId),
                Filters.eq("status", RunStatus.RUNNING),
                Filters.gte("started_at", threshold)
            )
        ).isPresent();
    }

    private void execute(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables) {
        if (definition.type == DefinitionType.LLM_CALL) {
            executeLLMCall(runEntity, definition);
        } else {
            executeAgent(runEntity, definition, sandbox, variables);
        }
    }

    private void executeAgent(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables) {
        var runId = runEntity.id;
        var agent = buildAgent(runEntity, definition, sandbox, variables);
        var completed = new AtomicBoolean(false);
        try {
            var config = definition.publishedConfig;
            var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds
                : definition.timeoutSeconds != null ? definition.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
            var timeout = scheduleTimeout(runEntity, agent, timeoutSeconds, completed);
            runWithTimeout(runEntity, agent, timeout, completed, definition);
        } catch (Exception e) {
            if (unwrapCause(e) instanceof MaxTurnsExceededException maxTurnsEx) {
                LOGGER.warn("agent run exceeded max turns, runId={}, maxTurns={}", runId, maxTurnsEx.maxTurns);
                if (completed.compareAndSet(false, true)) {
                    updateRunStatus(runEntity, RunStatus.COMPLETED, agent.getOutput(), "max turns reached: " + maxTurnsEx.maxTurns, agent);
                }
                return;
            }
            LOGGER.error("agent run failed, runId={}", runId, e);
            if (completed.compareAndSet(false, true)) {
                updateRunStatus(runEntity, RunStatus.FAILED, null, e.getMessage(), agent);
            }
        }
    }

    private void runWithTimeout(AgentRun runEntity, Agent agent, ScheduledFuture<?> timeout, AtomicBoolean completed, AgentDefinition definition) {
        try {
            if (completed.compareAndSet(false, true)) {
                var output = runAgentWithTrace(runEntity, definition, agent);
                updateRunStatus(runEntity, RunStatus.COMPLETED, output, null, agent);
                extractDatasetRecords(output, definition, runEntity.id, runEntity.agentId, runEntity.startedAt);
            }
        } finally {
            timeout.cancel(false);
        }
    }

    private void executeLLMCall(AgentRun runEntity, AgentDefinition definition) {
        try {
            var result = runLLMCallWithTrace(runEntity, definition);

            var tokenUsage = new TokenUsage();
            tokenUsage.input = result.inputTokens();
            tokenUsage.output = result.outputTokens();

            var config = definition.publishedConfig;
            var systemPrompt = resolveSystemPrompt(config, definition);

            runEntity.status = RunStatus.COMPLETED;
            runEntity.output = result.output();
            runEntity.completedAt = ZonedDateTime.now();
            runEntity.tokenUsage = tokenUsage;
            runEntity.transcript = buildLLMCallTranscript(systemPrompt, runEntity.input, result.output());
            agentRunCollection.replace(runEntity);
            extractDatasetRecords(result.output(), definition, runEntity.id, runEntity.agentId, runEntity.startedAt);
        } catch (Exception e) {
            LOGGER.error("llm call run failed, runId={}", runEntity.id, e);
            updateRunStatus(runEntity, RunStatus.FAILED, null, e.getMessage(), null);
        }
    }

    private List<TranscriptEntry> buildLLMCallTranscript(String systemPrompt, String input, String output) {
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

    private ScheduledFuture<?> scheduleTimeout(AgentRun runEntity, Agent agent, int timeoutSeconds, AtomicBoolean completed) {
        return timeoutScheduler.schedule(() -> {
            if (!completed.compareAndSet(false, true)) return;
            LOGGER.warn("agent run timed out, runId={}, timeoutSeconds={}", runEntity.id, timeoutSeconds);
            agent.cancel();
            updateRunStatus(runEntity, RunStatus.TIMEOUT, null, "execution timed out after " + timeoutSeconds + "s", agent);
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    @SuppressWarnings("checkstyle:MethodLength")
    private Agent buildAgent(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables) {
        var config = definition.publishedConfig;
        List<ToolCall> tools;
        if (config != null && config.tools != null && !config.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(config.tools);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.tools);
        } else {
            tools = List.of();
        }
        tools = new ArrayList<>(tools);
        if (sandbox != null) tools.add(SubmitArtifactsTool.create(definition.userId, fileService, new AgentRunArtifactSink(runEntity.id, agentRunCollection)));
        addDatasetTools(tools, config, definition, runEntity.id);
        var context = ExecutionContext.builder()
            .sessionId("run:" + runEntity.id)
            .userId(definition.userId)
            .customVariables(variables)
            .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, SubmitArtifactsTool.publicUrl))
            .build();
        if (sandbox != null) context.sandbox(sandbox);
        var systemPrompt = appendArtifactInstructions(resolveSystemPrompt(config, definition), sandbox != null);
        systemPrompt = appendDatasetInstructions(systemPrompt, config, definition);
        var model = config != null ? config.model : definition.model;
        var multiModalModel = config != null ? config.multiModalModel : definition.multiModalModel;
        var temperature = config != null ? config.temperature : definition.temperature;
        var maxTurns = config != null ? config.maxTurns : definition.maxTurns;
        var skillIds = IdLists.clean(config != null ? config.skillIds : definition.skillIds);
        SkillRegistry skillRegistry = null;
        if (!skillIds.isEmpty()) {
            skillRegistry = new SkillRegistry();
            skillRegistry.addProvider(mongoSkillProvider.scoped(new HashSet<>(skillIds)));
            tools.add(ServerSkillTool.builder()
                .registry(skillRegistry)
                .skillService(skillService)
                .archiveBuilder(skillArchiveBuilder)
                .build());
            tools.add(ReadSkillResourceTool.builder().registry(skillRegistry).build());
        }
        var builder = Agent.builder()
            .name(definition.name)
            .id(definition.id)
            .description(definition.description != null ? definition.description : definition.name)
            .llmProvider(llmProviders.getProvider())
            .toolCalls(tools)
            .executionContext(context);
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
        if (skillRegistry != null) builder.skillRegistry(skillRegistry);
        injectDatasetSystemVars(builder, config, definition);
        var memoryInject = agentMemoryService.buildMemoryPromptInject(definition.id);
        if (memoryInject != null) builder.systemPromptSection(memoryInject);
        var agent = builder.build();
        agent.setAuthenticated(true);
        return agent;
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private String runAgentWithTrace(AgentRun runEntity, AgentDefinition definition, Agent agent) {
        return runWithTrace(runEntity, definition, "agent.run", span -> {
            var output = agent.run(runEntity.input);
            if (output != null) span.setAttribute(OUTPUT_VALUE, output);
            if (agent.getNodeStatus() != null) span.setAttribute(AGENT_STATUS, agent.getNodeStatus().name());
            return output;
        });
    }

    private LLMCallExecutor.Result runLLMCallWithTrace(AgentRun runEntity, AgentDefinition definition) {
        return runWithTrace(runEntity, definition, "llm_call.run", span -> {
            var result = llmCallExecutor.execute(definition, runEntity.input);
            if (result.output() != null) span.setAttribute(OUTPUT_VALUE, result.output());
            return result;
        });
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private <T> T runWithTrace(AgentRun runEntity, AgentDefinition definition, String spanName, TraceCallable<T> callable) {
        var spanBuilder = telemetryConfig.getOpenTelemetry().getTracer("core-ai-server", "1.0.0")
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "agent")
            .setAttribute(GEN_AI_OPERATION_NAME, "agent")
            .setAttribute(SESSION_ID, "run:" + runEntity.id)
            .setAttribute(CLIENT_TYPE, traceSource(triggerFor(runEntity)))
            .setAttribute(CORE_AI_RUN_ID, runEntity.id);
        if (definition.id != null) spanBuilder.setAttribute(GEN_AI_AGENT_ID, definition.id);
        if (definition.name != null) spanBuilder.setAttribute(GEN_AI_AGENT_NAME, definition.name);
        if (definition.userId != null) spanBuilder.setAttribute(USER_ID, definition.userId);
        var span = spanBuilder.startSpan();
        var spanContext = span.getSpanContext();
        if (spanContext.isValid()) {
            setRunTraceId(runEntity, spanContext.getTraceId());
        }
        if (runEntity.scheduleId != null) span.setAttribute(CORE_AI_SCHEDULE_ID, runEntity.scheduleId);
        if (runEntity.input != null) span.setAttribute(INPUT_VALUE, runEntity.input);
        try (var scope = span.makeCurrent()) {
            return callable.call(span);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void setRunTraceId(AgentRun runEntity, String traceId) {
        if (traceId == null || traceId.isBlank()) return;
        runEntity.traceId = traceId;
        agentRunCollection.update(
            Filters.and(
                Filters.eq("_id", runEntity.id),
                Filters.or(Filters.exists("trace_id", false), Filters.eq("trace_id", null), Filters.eq("trace_id", ""))
            ),
            Updates.set("trace_id", traceId)
        );
    }

    @FunctionalInterface
    private interface TraceCallable<T> {
        T call(Span span);
    }

    private TriggerType triggerFor(AgentRun runEntity) {
        return runEntity.triggeredBy != null ? runEntity.triggeredBy : TriggerType.MANUAL;
    }

    private String traceSource(TriggerType trigger) {
        return switch (trigger) {
            case SCHEDULE -> "scheduled";
            case WEBHOOK, API, MANUAL -> "api";
            case WORKFLOW -> "workflow";
        };
    }

    private void addDatasetTools(List<ToolCall> tools, AgentPublishedConfig config, AgentDefinition definition, String runId) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var registry = DatasetAccessRegistry.from(datasetConfig);
        tools.add(QueryDatasetRecordsTool.create(datasetService, datasetRecordService, registry));
        if (registry.hasAnyWrite()) {
            tools.add(InsertDatasetRecordTool.create(definition.id, runId, datasetService, datasetRecordService, registry));
            tools.add(UpdateDatasetRecordTool.create(datasetService, datasetRecordService, registry));
        }
        if (registry.hasAnyFull()) {
            tools.add(DeleteDatasetRecordTool.create(datasetService, datasetRecordService, registry));
        }
    }

    private void updateRunStatus(AgentRun runEntity, RunStatus status, String output, String error, Agent agent) {
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

    private List<TranscriptEntry> buildTranscript(Agent agent) {
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

    private AgentRun createRunRecord(AgentDefinition definition, String input, TriggerType trigger, String scheduleId) {
        var entity = new AgentRun();
        entity.id = UUID.randomUUID().toString();
        entity.agentId = definition.id;
        entity.userId = definition.userId;
        entity.triggeredBy = trigger;
        entity.status = RunStatus.RUNNING;
        entity.input = input;
        entity.scheduleId = scheduleId;
        entity.startedAt = ZonedDateTime.now();
        return entity;
    }

    private String resolveSystemPrompt(AgentPublishedConfig config, AgentDefinition definition) {
        var promptId = config != null ? config.systemPromptId : definition.systemPromptId;
        if (promptId != null && !promptId.isBlank()) {
            return systemPromptService.resolveContent(promptId);
        }
        return config != null ? config.systemPrompt : definition.systemPrompt;
    }

    private String appendArtifactInstructions(String systemPrompt, boolean sandboxEnabled) {
        if (!sandboxEnabled) return systemPrompt;
        if (systemPrompt == null || systemPrompt.isBlank()) return SubmitArtifactsTool.SYSTEM_INSTRUCTIONS.strip();
        return systemPrompt + SubmitArtifactsTool.SYSTEM_INSTRUCTIONS;
    }

    private String appendDatasetInstructions(String systemPrompt, AgentPublishedConfig config, AgentDefinition definition) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return systemPrompt;
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    private void injectDatasetSystemVars(AgentBuilder builder, AgentPublishedConfig config, AgentDefinition definition) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var names = new ArrayList<String>();
        var desc = new StringBuilder();
        for (var cfg : datasetConfig) {
            var dataset = datasetService.get(cfg.datasetId);
            if (dataset == null) continue;
            names.add(dataset.name);
            desc.append("\n- \"").append(dataset.name).append("\" (").append(cfg.permission.name()).append(")");
            if (dataset.description != null && !dataset.description.isBlank()) {
                desc.append(": ").append(dataset.description);
            }
        }
        builder.extraSystemVariable(SystemVariables.AGENT_DATASET_NAME, String.join(", ", names));
        builder.extraSystemVariable(SystemVariables.AGENT_DATASET_DESC, desc.toString());
    }

    private Throwable unwrapCause(Throwable e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }

    private void extractDatasetRecords(String output, AgentDefinition definition, String runId, String agentId, ZonedDateTime runStartedAt) {
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
}
