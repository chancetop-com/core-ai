package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.agent.CancelReason;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.sandbox.Sandbox;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChannelTarget;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.runtime.CapacityExceededException;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.StagedFile;
import ai.core.server.sandboxhub.RunAgentRegistry;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class AgentRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_CONCURRENT_RUNS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    // backstop for the pool queue: the live bound (runtime.runQueueCapacity) is enforced at admission,
    // this only stops a burst from growing the queue between checks
    private static final int RUN_QUEUE_BACKSTOP = 500;
    private static final int RETRY_AFTER_SECONDS = 30;
    private static final int STALE_RUN_THRESHOLD_SECONDS = 1800;
    // ownership leases last a minute; a run outlives that, so it renews at half the lease
    private static final int OWNERSHIP_RENEW_SECONDS = 30;

    private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(MAX_CONCURRENT_RUNS, MAX_CONCURRENT_RUNS,
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(RUN_QUEUE_BACKSTOP),
            Executors.defaultThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();
    /** Queue depth and refusals of this pod's pool; the live capacity comes from system settings. */
    public final RunQueueAdmission admission = new RunQueueAdmission(() -> executorService.getQueue().size());
    private RunChannelDelivery channelDelivery;
    private RunSandboxReleaseScheduler sandboxReleases;

    @Inject
    SandboxService sandboxService;

    @Inject
    SkillService skillService;

    @Inject
    ChannelConfigStore channelConfigStore;

    @Inject
    ChannelRegistry channelRegistry;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    ai.core.server.project.ProjectArtifactBinder artifactBinder;

    @Inject
    LLMCallExecutor llmCallExecutor;

    @Inject
    AgentRunTracer tracer;
    @Inject
    AgentRunBuilder builder;
    @Inject
    RunAgentRegistry runAgents;
    @Inject
    SessionOwnershipRegistry ownershipRegistry;

    void requireTrustedDefinitionSkills(AgentDefinition definition) {
        if (definition == null) return;
        if (definition.publishedConfig != null) {
            if (!AgentDependencyAccessPolicy.hasValidatedPublishedSkills(definition.publishedConfig)) {
                throw new IllegalArgumentException("agent is unavailable");
            }
            return;
        }
        var skillIds = IdLists.clean(definition.skillIds);
        if (!skillIds.isEmpty()) skillService.resolveAccessibleSkills(skillIds, definition.userId);
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger) {
        return run(new RunParams(definition, input, trigger, null, null, null, null, null, null));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, List<LLMCallRequest.Attachment> attachments) {
        return run(new RunParams(definition, input, trigger, null, null, null, null, null, attachments));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId, Map<String, String> runtimeVariables) {
        return run(new RunParams(definition, input, trigger, null, scheduleId, runtimeVariables, null, null, null));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                      Map<String, String> runtimeVariables, ChannelTarget channel) {
        return run(new RunParams(definition, input, trigger, null, scheduleId, runtimeVariables, null, channel, null));
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                      Map<String, String> runtimeVariables, WorkflowRunContext workflowContext) {
        return run(new RunParams(definition, input, trigger, null, scheduleId, runtimeVariables, workflowContext, null, null));
    }

    private String run(RunParams params) {
        requireTrustedDefinitionSkills(params.definition);
        var resolvedVariables = new HashMap<String, Object>();
        if (params.runtimeVariables != null) {
            resolvedVariables.putAll(params.runtimeVariables);
        }
        var runEntity = createRunRecord(
            params.definition, params.input, params.trigger, params.scheduleId, params.callerUserId);
        agentRunCollection.insert(runEntity);
        rejectWhenRunQueueFull(runEntity);
        artifactBinder.bindRun(runEntity);   // schedule → subject binding, deterministic (no-op without a bound schedule)

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
            var execParams = new ExecuteAsyncParams(runEntity, params.definition, sandbox, resolvedVariables, traceContext, params.channel, staged, params.attachments);
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> executeAsync(execParams),
                    executorService);
            runningFutures.put(runId, future);
            future.whenComplete((result, error) -> {
                runningFutures.remove(runId);
                if (error != null) markRunFailedIfUnfinished(runEntity, error);
            });
        } catch (RejectedExecutionException e) {
            // the queue backstop tripped between the admission check and the submission: still a refusal
            rejectRun(runEntity, "run_queue");
            throw new CapacityExceededException("run_queue", RETRY_AFTER_SECONDS, "run queue is full, retry later", e);
        } catch (Exception e) {
            markRunFailedIfUnfinished(runEntity, e);
            throw e;
        }

        return runId;
    }

    public String runAs(AgentDefinition definition, String input, TriggerType trigger, String callerUserId) {
        if (callerUserId == null || callerUserId.isBlank()) {
            throw new IllegalArgumentException("caller user id is required");
        }
        return run(new RunParams(definition, input, trigger, callerUserId, null, null, null, null, null));
    }

    // run attributed to the caller user with runtime variables injected into tool arguments
    // (used by the project agent: project_id is auto-injected into every tool call)
    public String runAs(AgentDefinition definition, String input, TriggerType trigger, String callerUserId, Map<String, String> runtimeVariables) {
        if (callerUserId == null || callerUserId.isBlank()) {
            throw new IllegalArgumentException("caller user id is required");
        }
        return run(new RunParams(definition, input, trigger, callerUserId, null, runtimeVariables, null, null, null));
    }

    private void executeAsync(ExecuteAsyncParams params) {
        markRunning(params.runEntity);
        try {
            if (params.staged && params.sandbox != null) {
                sandboxService.ensurePendingFilesUploaded(params.runEntity.id);
            }
            execute(params.runEntity, params.definition, params.sandbox, params.resolvedVariables, params.traceContext, params.attachments);
            if (params.channel != null) {
                channelDelivery().send(params.runEntity, params.channel);
            }
        } finally {
            sandboxReleases().schedule(params.runEntity.id, params.runEntity.triggeredBy != null ? params.runEntity.triggeredBy : TriggerType.MANUAL);
        }
    }

    private void execute(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables,
                         WorkflowTraceContext traceContext, List<LLMCallRequest.Attachment> attachments) {
        if (definition.type == DefinitionType.LLM_CALL) {
            executeLLMCall(runEntity, definition, traceContext);
        } else {
            executeAgent(runEntity, definition, sandbox, variables, traceContext, attachments);
        }
    }

    private void executeAgent(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox, Map<String, Object> variables,
                              WorkflowTraceContext traceContext, List<LLMCallRequest.Attachment> attachments) {
        var completed = new AtomicBoolean(false);
        Agent agent = null;
        ScheduledFuture<?> ownershipLease = null;
        try {
            agent = builder.buildAgent(runEntity, definition, sandbox, variables, attachments);
            ownershipLease = publishRunAgent(runEntity.id, agent, sandbox);
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
                LOGGER.error("agent run failed, runId={}", runEntity.id, e);
                builder.updateRunStatus(runEntity, RunStatus.FAILED, null, AgentRunErrors.message(e), AgentRunErrors.stackTrace(e), agent);
            }
        } finally {
            if (ownershipLease != null) ownershipLease.cancel(false);
            if (agent != null) {
                runAgents.unregister(runEntity.id);
                if (ownershipRegistry != null) ownershipRegistry.release(runEntity.id);
            }
        }
    }

    /**
     * Makes the run's sandbox serve the hub like a session's does: the agent is published under the run id for
     * the duration of the run, and the replica claims (and keeps renewing) ownership of that id so every hub
     * call, wherever it arrives, is routed here.
     */
    private ScheduledFuture<?> publishRunAgent(String runId, Agent agent, Sandbox sandbox) {
        if (sandbox == null) return null;
        runAgents.register(runId, agent);
        if (ownershipRegistry == null) return null;
        if (!ownershipRegistry.claimOrRenew(runId)) {
            LOGGER.warn("failed to claim run ownership, sandbox hub calls for it may fail, runId={}", runId);
            return null;
        }
        // the ownership lease is shorter than a run, so the run keeps it fresh while it works
        return timeoutScheduler.scheduleAtFixedRate(() -> ownershipRegistry.renew(runId),
            OWNERSHIP_RENEW_SECONDS, OWNERSHIP_RENEW_SECONDS, TimeUnit.SECONDS);
    }

    private void runWithTimeout(AgentRun runEntity, Agent agent, ScheduledFuture<?> timeout, AtomicBoolean completed,
                                AgentDefinition definition, WorkflowTraceContext traceContext) {
        try {
            var output = tracer.runAgentWithTrace(runEntity, definition, agent, traceContext);
            if (!completed.compareAndSet(false, true)) return;
            builder.updateRunStatus(runEntity, RunStatus.COMPLETED, output, null, agent);
            builder.extractDatasetRecords(output, definition, runEntity.id, runEntity.agentId, runEntity.startedAt);
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
            LOGGER.error("llm run failed, runId={}", runEntity.id, e);
            builder.updateRunStatus(runEntity, RunStatus.FAILED, null, AgentRunErrors.message(e), AgentRunErrors.stackTrace(e), null);
        }
    }

    private void markRunFailedIfUnfinished(AgentRun runEntity, Throwable error) {
        var cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        var latest = agentRunCollection.get(runEntity.id).orElse(runEntity);
        if (latest.status != RunStatus.RUNNING && latest.status != RunStatus.PENDING) return;
        LOGGER.error("agent run failed asynchronously, runId={}", latest.id, cause);
        builder.updateRunStatus(latest, RunStatus.FAILED, null, AgentRunErrors.message(cause), AgentRunErrors.stackTrace(cause), null);
    }

    /**
     * The pool and the timeout scheduler must be drained in order: in-flight runs keep submitting
     * timeout and sandbox-release tasks, and a terminated scheduler would reject them and mark
     * otherwise-fine runs FAILED.
     */
    public void shutdown() {
        RunPools.awaitShutdown(executorService, timeoutScheduler);
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
                        Filters.in("status", List.of(RunStatus.RUNNING, RunStatus.PENDING)),
                        Filters.gte("started_at", threshold)
                )
        ).isPresent();
    }

    /** Runs executing on this pod right now (observation only — the pool bound itself is not yet enforced). */
    public int activeRunCount() {
        return runningFutures.size();
    }

    /** Runs accepted but still waiting for a pool thread. */
    public int queuedRunCount() {
        return admission.queued();
    }

    public long rejectedRunTotal() {
        return admission.rejectedTotal();
    }

    /**
     * Rejects before any sandbox or pool slot is taken. The run record is kept (status REJECTED) so the
     * caller and the run list both see why, and the caller gets a retryable 429 instead of a silent queue.
     */
    private void rejectWhenRunQueueFull(AgentRun runEntity) {
        if (!admission.queueFull()) return;
        var queued = admission.queued();
        rejectRun(runEntity, "run_queue");
        throw new CapacityExceededException("run_queue", RETRY_AFTER_SECONDS,
                "run queue is full (" + queued + " waiting), retry later");
    }

    private void rejectRun(AgentRun runEntity, String scope) {
        admission.recordRejection();
        runEntity.status = RunStatus.REJECTED;
        runEntity.completedAt = ZonedDateTime.now();
        runEntity.error = "rejected by runtime admission: " + scope;
        agentRunCollection.replace(runEntity);
        LOGGER.warn("run rejected by runtime admission, runId={}, scope={}, queued={}", runEntity.id, scope, admission.queued());
    }

    /** The queue wait ends here: the run is executing, so started_at stops meaning "accepted". */
    private void markRunning(AgentRun runEntity) {
        runEntity.status = RunStatus.RUNNING;
        runEntity.startedAt = ZonedDateTime.now();
        agentRunCollection.replace(runEntity);
    }

    private RunChannelDelivery channelDelivery() {
        if (channelDelivery == null) channelDelivery = new RunChannelDelivery(channelConfigStore, channelRegistry);
        return channelDelivery;
    }

    private RunSandboxReleaseScheduler sandboxReleases() {
        if (sandboxReleases == null) sandboxReleases = new RunSandboxReleaseScheduler(sandboxService, timeoutScheduler);
        return sandboxReleases;
    }

    private ScheduledFuture<?> scheduleTimeout(AgentRun runEntity, Agent agent, int timeoutSeconds, AtomicBoolean completed) {
        return timeoutScheduler.schedule(() -> {
            if (!completed.compareAndSet(false, true)) return;
            agent.cancel(CancelReason.TIMEOUT);
            builder.updateRunStatus(runEntity, RunStatus.TIMEOUT, null, "execution timed out after " + timeoutSeconds + "s", agent);
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    AgentRun createRunRecord(AgentDefinition definition, String input, TriggerType trigger, String scheduleId,
                             String callerUserId) {
        var entity = new AgentRun();
        entity.id = UUID.randomUUID().toString();
        entity.agentId = definition.id;
        entity.userId = callerUserId != null && !callerUserId.isBlank() ? callerUserId : definition.userId;
        entity.triggeredBy = trigger;
        entity.status = RunStatus.PENDING;
        entity.input = input;
        entity.scheduleId = scheduleId;
        entity.startedAt = ZonedDateTime.now();
        entity.queuedAt = entity.startedAt;
        return entity;
    }

    private Throwable unwrapCause(Throwable e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }

    record RunParams(AgentDefinition definition, String input, TriggerType trigger, String callerUserId,
                     String scheduleId,
                     Map<String, String> runtimeVariables, WorkflowRunContext workflowContext,
                     ChannelTarget channel, List<LLMCallRequest.Attachment> attachments) {
    }

    private record ExecuteAsyncParams(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox,
                                      Map<String, Object> resolvedVariables, WorkflowTraceContext traceContext,
                                      ChannelTarget channel, boolean staged, List<LLMCallRequest.Attachment> attachments) {
    }
}
