package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.llm.LLMProviders;
import ai.core.sandbox.Sandbox;
import ai.core.server.domain.AgentDefinition;
import ai.core.tool.ToolCall;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TokenUsage;
import ai.core.server.domain.TranscriptEntry;
import ai.core.server.domain.TriggerType;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.skill.SkillRegistry;
import ai.core.tool.tools.ReadSkillResourceTool;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
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
    private static final int MAX_CONCURRENT_RUNS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int SANDBOX_RELEASE_DELAY_SECONDS = 60;
    private static final int MAX_TRANSCRIPT_RESULT_LENGTH = 10240;
    private static final String ARTIFACT_SYSTEM_INSTRUCTIONS = """

        # Platform artifact delivery

        When you create or update files in the sandbox that are intended for the caller to download or reuse
        (for example PDFs, reports, charts, CSVs, spreadsheets, images, or archives), you must call the
        `submit_artifacts` tool before your final response. Submit the sandbox file paths, usually under
        `/tmp` or `/workspace`, with concise names and content types when known.

        This is a platform delivery requirement. It does not change the user's requested final response format:
        after submitting artifacts, still answer exactly as the task instructions require.
        """;

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
    MongoCollection<AgentRun> agentRunCollection;

    @Inject
    SandboxService sandboxService;

    @Inject
    FileService fileService;

    public String run(AgentDefinition definition, String input, TriggerType trigger) {
        return run(definition, input, trigger, null);
    }

    public String run(AgentDefinition definition, String input, TriggerType trigger, Map<String, String> runtimeVariables) {
        var resolvedVariables = new HashMap<String, Object>();
        if (runtimeVariables != null) {
            resolvedVariables.putAll(runtimeVariables);
        }
        var runEntity = createRunRecord(definition, input, trigger);
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

    public boolean isRunning(String agentId) {
        return agentRunCollection.findOne(
            Filters.and(
                Filters.eq("agent_id", agentId),
                Filters.eq("status", RunStatus.RUNNING)
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
            runWithTimeout(runEntity, agent, timeout, completed);
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

    private void runWithTimeout(AgentRun runEntity, Agent agent, ScheduledFuture<?> timeout, AtomicBoolean completed) {
        try {
            if (completed.compareAndSet(false, true)) {
                var output = agent.run(runEntity.input);
                updateRunStatus(runEntity, RunStatus.COMPLETED, output, null, agent);
            }
        } finally {
            timeout.cancel(false);
        }
    }

    private void executeLLMCall(AgentRun runEntity, AgentDefinition definition) {
        try {
            var result = llmCallExecutor.execute(definition, runEntity.input);

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
        if (sandbox != null) tools.add(SubmitArtifactsTool.create(runEntity.id, definition.userId, fileService, agentRunCollection));
        var context = ExecutionContext.builder()
            .sessionId("run:" + definition.id)
            .userId(definition.userId)
            .customVariables(variables)
            .build();
        if (sandbox != null) context.sandbox(sandbox);
        var systemPrompt = appendArtifactInstructions(resolveSystemPrompt(config, definition), sandbox != null);
        var model = config != null ? config.model : definition.model;
        var temperature = config != null ? config.temperature : definition.temperature;
        var maxTurns = config != null ? config.maxTurns : definition.maxTurns;
        var skillIds = config != null ? config.skillIds : definition.skillIds;
        SkillRegistry skillRegistry = null;
        if (skillIds != null && !skillIds.isEmpty()) {
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
            .llmProvider(llmProviders.getProvider())
            .toolCalls(tools)
            .executionContext(context);
        if (systemPrompt != null) builder.systemPrompt(systemPrompt);
        if (model != null) builder.model(model);
        if (temperature != null) builder.temperature(temperature);
        if (maxTurns != null) builder.maxTurn(maxTurns);
        if (skillRegistry != null) builder.skillRegistry(skillRegistry);
        var agent = builder.build();
        agent.setAuthenticated(true);
        return agent;
    }

    private void updateRunStatus(AgentRun runEntity, RunStatus status, String output, String error, Agent agent) {
        var latestRun = agentRunCollection.get(runEntity.id).orElse(runEntity);
        runEntity.artifacts = latestRun.artifacts;
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

    private AgentRun createRunRecord(AgentDefinition definition, String input, TriggerType trigger) {
        var entity = new AgentRun();
        entity.id = UUID.randomUUID().toString();
        entity.agentId = definition.id;
        entity.userId = definition.userId;
        entity.triggeredBy = trigger;
        entity.status = RunStatus.RUNNING;
        entity.input = input;
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
        if (systemPrompt == null || systemPrompt.isBlank()) return ARTIFACT_SYSTEM_INSTRUCTIONS.strip();
        return systemPrompt + ARTIFACT_SYSTEM_INSTRUCTIONS;
    }

    private Throwable unwrapCause(Throwable e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }
}
