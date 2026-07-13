package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.sandbox.Sandbox;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelMessage;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.StagedFile;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class AgentRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_CONCURRENT_RUNS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int SANDBOX_RELEASE_DELAY_SECONDS = 60;
    private static final int WORKFLOW_SANDBOX_RELEASE_DELAY_SECONDS = 10;
    private static final int STALE_RUN_THRESHOLD_SECONDS = 1800;

    private final ExecutorService executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();

    @Inject
    SandboxService sandboxService;

    @Inject
    ChannelConfigStore channelConfigStore;

    @Inject
    ChannelRegistry channelRegistry;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    @Inject
    LLMCallExecutor llmCallExecutor;

    @Inject
    AgentRunTracer tracer;
    @Inject
    AgentRunBuilder builder;

    public String run(AgentDefinition definition, String input, TriggerType trigger) {
        return run(new RunParams(definition, input, trigger, null, null, null, null));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId, Map<String, String> runtimeVariables) {
        return run(new RunParams(definition, input, trigger, scheduleId, runtimeVariables, null, null));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                      Map<String, String> runtimeVariables, ChannelTarget channel) {
        return run(new RunParams(definition, input, trigger, scheduleId, runtimeVariables, null, channel));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                      Map<String, String> runtimeVariables, WorkflowRunContext workflowContext) {
        return run(new RunParams(definition, input, trigger, scheduleId, runtimeVariables, workflowContext, null));
    }

    private String run(RunParams params) {
        var resolvedVariables = new HashMap<String, Object>();
        if (params.runtimeVariables != null) {
            resolvedVariables.putAll(params.runtimeVariables);
        }
        var runEntity = createRunRecord(params.definition, params.input, params.trigger, params.scheduleId);
        agentRunCollection.insert(runEntity);

        var runId = runEntity.id;
        var workflowContext = params.workflowContext;
        var traceContext = workflowContext == null ? null : workflowContext.trace();
        var stagedFiles = workflowContext == null ? List.<StagedFile>of() : workflowContext.stagedFiles();

        try {
            var sandboxConfig = sandboxService.getEffectiveConfig(params.definition);
            boolean staged = stagedFiles != null && !stagedFiles.isEmpty();
            if (staged) {
                for (var file : stagedFiles) {
                    sandboxService.addStagedFile(runId, file);
                }
            }
            var sandbox = sandboxService.createSandbox(sandboxConfig, runId, params.definition.userId);
            var execParams = new ExecuteAsyncParams(runEntity, params.definition, sandbox, resolvedVariables, traceContext, params.channel, staged);
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> executeAsync(execParams),
                    executorService);
            runningFutures.put(runId, future);
            future.whenComplete((result, error) -> {
                runningFutures.remove(runId);
                if (error != null) markRunFailedIfUnfinished(runEntity, error);
            });
        } catch (Exception e) {
            markRunFailedIfUnfinished(runEntity, e);
            throw e;
        }

        return runId;
    }

    private void executeAsync(ExecuteAsyncParams params) {
        try {
            if (params.staged && params.sandbox != null) {
                sandboxService.ensurePendingFilesUploaded(params.runEntity.id);
            }
            execute(params.runEntity, params.definition, params.sandbox, params.resolvedVariables, params.traceContext);
            if (params.channel != null) {
                sendToChannelIfConfigured(params.runEntity, params.channel);
            }
        } finally {
            scheduleSandboxRelease(params.runEntity.id, params.runEntity.triggeredBy != null ? params.runEntity.triggeredBy : TriggerType.MANUAL);
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
        var completed = new AtomicBoolean(false);
        Agent agent = null;
        try {
            agent = builder.buildAgent(runEntity, definition, sandbox, variables);
            var config = definition.publishedConfig;
            var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds
                    : definition.timeoutSeconds != null ? definition.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
            var timeout = scheduleTimeout(runEntity, agent, timeoutSeconds, completed);
            runWithTimeout(runEntity, agent, timeout, completed, definition, traceContext);
        } catch (Exception e) {
            if (agent != null && unwrapCause(e) instanceof MaxTurnsExceededException maxTurnsEx) {
                if (completed.compareAndSet(false, true)) {
                    builder.updateRunStatus(runEntity, RunStatus.COMPLETED, agent.getOutput(), "max turns reached: " + maxTurnsEx.maxTurns, agent);
                }
                return;
            }
            if (completed.compareAndSet(false, true)) {
                builder.updateRunStatus(runEntity, RunStatus.FAILED, null, errorMessage(e), agent);
            }
        }
    }

    private void runWithTimeout(AgentRun runEntity, Agent agent, ScheduledFuture<?> timeout, AtomicBoolean completed,
                                AgentDefinition definition, WorkflowTraceContext traceContext) {
        try {
            @SuppressWarnings("checkstyle:MoveVariableInsideIf")
            var output = tracer.runAgentWithTrace(runEntity, definition, agent, traceContext);
            if (completed.compareAndSet(false, true)) {
                builder.updateRunStatus(runEntity, RunStatus.COMPLETED, output, null, agent);
                builder.extractDatasetRecords(output, definition, runEntity.id, runEntity.agentId, runEntity.startedAt);
            }
        } finally {
            timeout.cancel(false);
        }
    }

    private void executeLLMCall(AgentRun runEntity, AgentDefinition definition, WorkflowTraceContext traceContext) {
        try {
            var result = tracer.runLLMCallWithTrace(runEntity, definition, traceContext, llmCallExecutor);
            var config = definition.publishedConfig;
            var systemPrompt = config != null && config.systemPromptId != null ? null : "llm_call";
            runEntity.status = RunStatus.COMPLETED;
            runEntity.output = result.output();
            runEntity.completedAt = ZonedDateTime.now();
            runEntity.transcript = builder.buildLLMCallTranscript(systemPrompt, runEntity.input, result.output());
            agentRunCollection.replace(runEntity);
            builder.extractDatasetRecords(result.output(), definition, runEntity.id, runEntity.agentId, runEntity.startedAt);
        } catch (Exception e) {
            builder.updateRunStatus(runEntity, RunStatus.FAILED, null, e.getMessage(), null);
        }
    }

    private void markRunFailedIfUnfinished(AgentRun runEntity, Throwable error) {
        var cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        var latest = agentRunCollection.get(runEntity.id).orElse(runEntity);
        if (latest.status != RunStatus.RUNNING && latest.status != RunStatus.PENDING) return;
        builder.updateRunStatus(latest, RunStatus.FAILED, null, errorMessage(cause), null);
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
                LOGGER.debug("error releasing sandbox for run {}, ignored", runId, e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public void shutdown() {
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

    private ScheduledFuture<?> scheduleTimeout(AgentRun runEntity, Agent agent, int timeoutSeconds, AtomicBoolean completed) {
        return timeoutScheduler.schedule(() -> {
            if (!completed.compareAndSet(false, true)) return;
            agent.cancel();
            builder.updateRunStatus(runEntity, RunStatus.TIMEOUT, null, "execution timed out after " + timeoutSeconds + "s", agent);
        }, timeoutSeconds, TimeUnit.SECONDS);
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

    private Throwable unwrapCause(Throwable e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }

    private void sendToChannelIfConfigured(AgentRun runEntity, ChannelTarget channel) {
        var channelId = channel.id;
        var channelRecipientId = channel.recipientId;
        if (channelId == null || channelId.isBlank()) return;
        if (channelRecipientId == null || channelRecipientId.isBlank()) return;

        var channelConfig = channelConfigStore.load(channelId);
        if (channelConfig == null) return;

        try {
            var outbound = channelRegistry.outbound(channelConfig.channelType);
            var message = ChannelMessage.text(runEntity.output);
            outbound.sendMessage(message, channelRecipientId, channelRecipientId, null, channelConfig.config);
        } catch (Exception e) {
            LOGGER.debug("channel delivery failed for run {}, ignored", runEntity.id, e);
        }
    }

    public record ChannelTarget(String id, String recipientId) {
    }

    record RunParams(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                     Map<String, String> runtimeVariables, WorkflowRunContext workflowContext,
                     ChannelTarget channel) {
    }

    private record ExecuteAsyncParams(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox,
                                      Map<String, Object> resolvedVariables, WorkflowTraceContext traceContext,
                                      ChannelTarget channel, boolean staged) {
    }
}
