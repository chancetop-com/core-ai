package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.llm.LLMProviders;
import ai.core.sandbox.Sandbox;
import ai.core.server.artifact.AgentRunArtifactSink;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.sandbox.SandboxLifecycle;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.memory.AgentMemoryService;
import ai.core.server.memory.SearchMemoryTool;
import ai.core.tool.registry.ListToolProvider;
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
import ai.core.server.dataset.tool.DatasetToolProvider;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.StagedFile;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.skill.SkillToolAssembler;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.registry.ToolRegistry;
import ai.core.telemetry.TelemetryConfig;
import ai.core.tool.tools.InternalUrlResolver;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private static final AttributeKey<String> CORE_AI_WORKFLOW_ID = AttributeKey.stringKey("core_ai.workflow_id");
    private static final AttributeKey<String> CORE_AI_WORKFLOW_RUN_ID = AttributeKey.stringKey("core_ai.workflow_run_id");
    private static final AttributeKey<String> CORE_AI_WORKFLOW_NODE_ID = AttributeKey.stringKey("core_ai.workflow_node_id");
    private static final AttributeKey<String> CORE_AI_WORKFLOW_NODE_TYPE = AttributeKey.stringKey("core_ai.workflow_node_type");
    private static final int MAX_CONCURRENT_RUNS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int SANDBOX_RELEASE_DELAY_SECONDS = 60;
    // Workflow AGENT/LLM nodes are decoupled one-shot child runs: the node's awaitResult unblocks the moment the
    // run reaches a terminal status and nothing reuses this run's sandbox afterwards, so release it on a short
    // delay instead of the full grace window — under fan-out the long delay would leave many idle sandboxes alive
    // at once. The small non-zero buffer still absorbs any in-flight terminal-status write.
    private static final int WORKFLOW_SANDBOX_RELEASE_DELAY_SECONDS = 10;
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
    SkillToolAssembler skillToolAssembler;

    @Inject
    SubAgentAssembler subAgentAssembler;

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
        return run(definition, input, trigger, scheduleId, runtimeVariables, null);
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                      Map<String, String> runtimeVariables, WorkflowRunContext workflowContext) {
        var resolvedVariables = new HashMap<String, Object>();
        if (runtimeVariables != null) {
            resolvedVariables.putAll(runtimeVariables);
        }
        var runEntity = createRunRecord(definition, input, trigger, scheduleId);
        agentRunCollection.insert(runEntity);

        var runId = runEntity.id;
        var traceContext = workflowContext == null ? null : workflowContext.trace();
        var stagedFiles = workflowContext == null ? List.<StagedFile>of() : workflowContext.stagedFiles();

        try {
            // Create sandbox with effective config (platform default + agent override)
            var sandboxConfig = sandboxService.getEffectiveConfig(definition);

            // queue staged workflow input files BEFORE the sandbox exists, so they land on materialization
            boolean staged = stagedFiles != null && !stagedFiles.isEmpty();
            if (staged) {
                for (var file : stagedFiles) {
                    sandboxService.addStagedFile(runId, file);
                }
            }
            var sandbox = sandboxService.createSandbox(sandboxConfig, runId, definition.userId);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // staged files must be in place before the agent loop starts; a staging failure is
                    // deterministic (fails the run) instead of letting the agent run with missing inputs
                    if (staged && sandbox != null) {
                        sandboxService.ensurePendingFilesUploaded(runId);
                    }
                    execute(runEntity, definition, sandbox, resolvedVariables, traceContext);
                } finally {
                    scheduleSandboxRelease(runId, trigger);
                }
            }, executorService);
            runningFutures.put(runId, future);
            future.whenComplete((result, error) -> {
                runningFutures.remove(runId);
                // backstop: anything escaping execute() (e.g. Error, or bugs outside its catches)
                // must not leave the run stuck in RUNNING forever
                if (error != null) markRunFailedIfUnfinished(runEntity, error);
            });
        } catch (Exception e) {
            markRunFailedIfUnfinished(runEntity, e);
            throw e;
        }

        return runId;
    }

    private void markRunFailedIfUnfinished(AgentRun runEntity, Throwable error) {
        var cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        LOGGER.error("agent run terminated unexpectedly, runId={}", runEntity.id, cause);
        var latest = agentRunCollection.get(runEntity.id).orElse(runEntity);
        if (latest.status != RunStatus.RUNNING && latest.status != RunStatus.PENDING) return;
        updateRunStatus(latest, RunStatus.FAILED, null, errorMessage(cause), null);
    }

    private String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    private void scheduleSandboxRelease(String runId, TriggerType trigger) {
        var delaySeconds = trigger == TriggerType.WORKFLOW ? WORKFLOW_SANDBOX_RELEASE_DELAY_SECONDS : SANDBOX_RELEASE_DELAY_SECONDS;
        timeoutScheduler.schedule(() -> {
            try {
                sandboxService.releaseSandbox(runId);
            } catch (Exception e) {
                LOGGER.warn("failed to release sandbox for runId={}", runId, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Gracefully shut down the executor and timeout scheduler, releasing their thread pools.
     * Called during application shutdown to prevent thread leaks.
     */
    public void shutdown() {
        LOGGER.info("shutting down agent runner");
        executorService.shutdown();
        timeoutScheduler.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            timeoutScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("agent runner shutdown complete");
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

    public record WorkflowTraceContext(String workflowId, String workflowRunId, String workflowNodeId, String workflowNodeType) {
    }

    /** Everything a workflow-origin run carries beyond the plain agent run: the trace linkage and the upstream
     *  artifact files the platform stages into the child sandbox before the agent loop starts. */
    public record WorkflowRunContext(WorkflowTraceContext trace, List<StagedFile> stagedFiles) {
        public WorkflowRunContext {
            stagedFiles = stagedFiles == null ? List.of() : List.copyOf(stagedFiles);
        }
    }

    private void execute(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables,
                         WorkflowTraceContext traceContext) {
        if (definition.type == DefinitionType.LLM_CALL) {
            executeLLMCall(runEntity, definition, traceContext);
        } else {
            executeAgent(runEntity, definition, sandbox, variables, traceContext);
        }
    }

    private void executeAgent(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables,
                              WorkflowTraceContext traceContext) {
        var runId = runEntity.id;
        var completed = new AtomicBoolean(false);
        Agent agent = null;
        try {
            agent = buildAgent(runEntity, definition, sandbox, variables);
            var config = definition.publishedConfig;
            var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds
                : definition.timeoutSeconds != null ? definition.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
            var timeout = scheduleTimeout(runEntity, agent, timeoutSeconds, completed);
            runWithTimeout(runEntity, agent, timeout, completed, definition, traceContext);
        } catch (Exception e) {
            if (agent != null && unwrapCause(e) instanceof MaxTurnsExceededException maxTurnsEx) {
                LOGGER.warn("agent run exceeded max turns, runId={}, maxTurns={}", runId, maxTurnsEx.maxTurns);
                if (completed.compareAndSet(false, true)) {
                    updateRunStatus(runEntity, RunStatus.COMPLETED, agent.getOutput(), "max turns reached: " + maxTurnsEx.maxTurns, agent);
                }
                return;
            }
            LOGGER.error("agent run failed, runId={}", runId, e);
            if (completed.compareAndSet(false, true)) {
                updateRunStatus(runEntity, RunStatus.FAILED, null, errorMessage(e), agent);
            }
        }
    }

    private void runWithTimeout(AgentRun runEntity, Agent agent, ScheduledFuture<?> timeout, AtomicBoolean completed,
                                AgentDefinition definition, WorkflowTraceContext traceContext) {
        try {
            var output = runAgentWithTrace(runEntity, definition, agent, traceContext);
            // claim completion only after the run finishes, so the timeout callback
            // can still fire and mark TIMEOUT while agent.run() is in flight
            if (completed.compareAndSet(false, true)) {
                updateRunStatus(runEntity, RunStatus.COMPLETED, output, null, agent);
                extractDatasetRecords(output, definition, runEntity.id, runEntity.agentId, runEntity.startedAt);
            }
        } finally {
            timeout.cancel(false);
        }
    }

    private void executeLLMCall(AgentRun runEntity, AgentDefinition definition, WorkflowTraceContext traceContext) {
        try {
            var result = runLLMCallWithTrace(runEntity, definition, traceContext);

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
        List<ToolRef> toolRefs;
        if (config != null && config.tools != null && !config.tools.isEmpty()) {
            toolRefs = config.tools;
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            toolRefs = definition.tools;
        } else {
            toolRefs = List.of();
        }
        var registry = toolRegistryService.resolveToToolRegistry(toolRefs, runEntity.id);
        addDatasetTools(registry, config, definition, runEntity.id);
        var context = ExecutionContext.builder()
            .sessionId("run:" + runEntity.id)
            .userId(definition.userId)
            .customVariables(variables)
            .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, SubmitArtifactsTool.publicUrl))
            .build();
        if (sandbox != null) context.sandbox(sandbox);
        var systemPrompt = resolveSystemPrompt(config, definition);
        systemPrompt = appendDatasetInstructions(systemPrompt, config, definition);
        var enableMemory = config != null ? config.enableMemory : definition.enableMemory;
        if (AgentMemoryService.memoryEnabled(enableMemory)) {
            systemPrompt = appendSopPriorityDeclaration(systemPrompt);
        }
        var model = config != null ? config.model : definition.model;
        var multiModalModel = config != null ? config.multiModalModel : definition.multiModalModel;
        var temperature = config != null ? config.temperature : definition.temperature;
        var maxTurns = config != null ? config.maxTurns : definition.maxTurns;
        skillToolAssembler.attach(config != null ? config.skillIds : definition.skillIds, registry);
        var subAgents = subAgentAssembler.assemble(config != null ? config.subAgentIds : definition.subAgentIds, runEntity.id);
        if (!subAgents.isEmpty()) {
            registry.registerProvider(ListToolProvider.of("sub-agents", new ArrayList<>(subAgents)));
        }
        if (AgentMemoryService.memoryEnabled(enableMemory)) {
            var searchTool = new SearchMemoryTool(definition.id, agentMemoryService);
            registry.registerProvider(ListToolProvider.of("search-memory", List.of(searchTool)));
        }
        var builder = Agent.builder()
            .name(safeNodeName(definition))
            .id(definition.id)
            .description(definition.description != null ? definition.description : definition.name)
            .llmProvider(llmProviders.getProvider())
            .toolRegistry(registry)
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
        injectDatasetSystemVars(builder, config, definition);
        if (AgentMemoryService.memoryEnabled(enableMemory)) {
            var memoryInject = agentMemoryService.buildMemoryPromptInject(definition.id);
            if (memoryInject != null) builder.systemPromptSection(memoryInject);
        }
        if (sandbox != null) {
            builder.addAgentLifecycle(new SandboxLifecycle(fileService,
                    new AgentRunArtifactSink(runEntity.id, agentRunCollection)));
        }
        var agent = builder.build();
        agent.setAuthenticated(true);
        return agent;
    }

    // The display name is user-facing and may contain spaces; the framework node name must be tool-name-safe
    // (it becomes an LLM function name, Node enforces ^[^\s<|\\/>]+$). Sanitize at this boundary instead of
    // constraining what users may call their agents; null-safe for workflow transient definitions (no name).
    static String safeNodeName(AgentDefinition definition) {
        var name = definition.name != null && !definition.name.isBlank() ? definition.name : "agent-" + definition.id;
        return name.trim().replaceAll("[\\s<|\\\\/>]+", "-");
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private String runAgentWithTrace(AgentRun runEntity, AgentDefinition definition, Agent agent,
                                     WorkflowTraceContext traceContext) {
        return runWithTrace(runEntity, definition, traceContext, "agent.run", span -> {
            var output = agent.run(runEntity.input);
            if (output != null) span.setAttribute(OUTPUT_VALUE, output);
            if (agent.getNodeStatus() != null) span.setAttribute(AGENT_STATUS, agent.getNodeStatus().name());
            return output;
        });
    }

    private LLMCallExecutor.Result runLLMCallWithTrace(AgentRun runEntity, AgentDefinition definition,
                                                       WorkflowTraceContext traceContext) {
        return runWithTrace(runEntity, definition, traceContext, "llm_call.run", span -> {
            var result = llmCallExecutor.execute(definition, runEntity.input);
            if (result.output() != null) span.setAttribute(OUTPUT_VALUE, result.output());
            return result;
        });
    }

    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private <T> T runWithTrace(AgentRun runEntity, AgentDefinition definition, WorkflowTraceContext traceContext,
                               String spanName, TraceCallable<T> callable) {
        var spanBuilder = telemetryConfig.getOpenTelemetry().getTracer("core-ai-server", "1.0.0")
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(LANGFUSE_OBSERVATION_TYPE, "agent")
            .setAttribute(GEN_AI_OPERATION_NAME, "agent")
            .setAttribute(SESSION_ID, "run:" + runEntity.id)
            .setAttribute(CLIENT_TYPE, traceSource(triggerFor(runEntity)))
            .setAttribute(CORE_AI_RUN_ID, runEntity.id);
        addWorkflowTraceAttributes(spanBuilder, traceContext);
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

    private void addWorkflowTraceAttributes(SpanBuilder spanBuilder, WorkflowTraceContext traceContext) {
        if (traceContext == null) return;
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_ID, traceContext.workflowId);
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_RUN_ID, traceContext.workflowRunId);
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_NODE_ID, traceContext.workflowNodeId);
        setSpanAttribute(spanBuilder, CORE_AI_WORKFLOW_NODE_TYPE, traceContext.workflowNodeType);
    }

    private void setSpanAttribute(SpanBuilder spanBuilder, AttributeKey<String> key, String value) {
        if (value != null && !value.isBlank()) spanBuilder.setAttribute(key, value);
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

    private void addDatasetTools(ToolRegistry registry, AgentPublishedConfig config, AgentDefinition definition, String runId) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return;
        var accessRegistry = DatasetAccessRegistry.from(datasetConfig);
        registry.registerProvider(new DatasetToolProvider(datasetService, datasetRecordService, accessRegistry, definition.id, runId));
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

    private String appendDatasetInstructions(String systemPrompt, AgentPublishedConfig config, AgentDefinition definition) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (datasetConfig == null || datasetConfig.isEmpty()) return systemPrompt;
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    static String appendSopPriorityDeclaration(String systemPrompt) {
        var declaration = """
                \n
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
