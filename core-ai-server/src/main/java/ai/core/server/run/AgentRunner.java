package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TokenUsage;
import ai.core.server.domain.TranscriptEntry;
import ai.core.server.domain.TriggerType;
import ai.core.server.tool.ToolRegistryService;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author stephen
 */
public class AgentRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_CONCURRENT_RUNS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int MAX_TRANSCRIPT_RESULT_LENGTH = 10240;

    private final ExecutorService executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_RUNS);
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();

    @Inject
    LLMProviders llmProviders;

    @Inject
    ToolRegistryService toolRegistryService;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    public String run(AgentDefinition definition, String input, TriggerType trigger) {
        var runEntity = createRunRecord(definition, input, trigger);
        agentRunCollection.insert(runEntity);

        var runId = runEntity.id;
        var future = CompletableFuture.runAsync(() -> execute(runEntity, definition), executorService);
        runningFutures.put(runId, future);
        future.whenComplete((result, error) -> runningFutures.remove(runId));

        return runId;
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

    private void execute(AgentRun runEntity, AgentDefinition definition) {
        if (definition.type == DefinitionType.LLM_CALL) {
            executeLLMCall(runEntity, definition);
        } else {
            executeAgent(runEntity, definition);
        }
    }

    private void executeAgent(AgentRun runEntity, AgentDefinition definition) {
        var runId = runEntity.id;
        try {
            var agent = buildAgent(definition);
            var config = definition.publishedConfig;
            var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds
                : definition.timeoutSeconds != null ? definition.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
            var output = executeWithTimeout(agent, runEntity.input, timeoutSeconds);
            updateRunStatus(runEntity, RunStatus.COMPLETED, output, null, agent);
        } catch (TimeoutException e) {
            var config = definition.publishedConfig;
            var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds
                : definition.timeoutSeconds != null ? definition.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
            updateRunStatus(runEntity, RunStatus.TIMEOUT, null, "execution timed out after " + timeoutSeconds + "s", null);
        } catch (Exception e) {
            LOGGER.error("agent run failed, runId={}", runId, e);
            updateRunStatus(runEntity, RunStatus.FAILED, null, e.getMessage(), null);
        }
    }

    private void executeLLMCall(AgentRun runEntity, AgentDefinition definition) {
        var runId = runEntity.id;
        var config = definition.publishedConfig;
        var responseSchemaJson = config != null ? config.responseSchema : definition.responseSchema;
        try {
            var systemPrompt = config != null ? config.systemPrompt : definition.systemPrompt;
            var model = config != null ? config.model : definition.model;
            var temperature = config != null ? config.temperature : definition.temperature;
            var timeoutSeconds = config != null && config.timeoutSeconds != null ? config.timeoutSeconds
                : definition.timeoutSeconds != null ? definition.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;

            ResponseFormat responseFormat = null;
            if (responseSchemaJson != null) {
                List<ApiDefinitionType> responseSchemaTypes = JsonUtil.fromJson(new TypeReference<>() { }, responseSchemaJson);
                responseFormat = ResponseSchemaConverter.toResponseFormat(responseSchemaTypes);
            }

            var messages = new ArrayList<Message>();
            if (systemPrompt != null) {
                messages.add(Message.of(RoleType.SYSTEM, systemPrompt));
            }
            messages.add(Message.of(RoleType.USER, runEntity.input));

            var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
                messages, null, temperature, model, null, false, responseFormat, null
            ));
            request.setTimeoutSeconds(timeoutSeconds);

            var provider = llmProviders.getProvider();
            var response = provider.completion(request);

            var output = response.choices.getFirst().message.content;
            var tokenUsage = new TokenUsage();
            if (response.usage != null) {
                tokenUsage.input = (long) response.usage.getPromptTokens();
                tokenUsage.output = (long) response.usage.getCompletionTokens();
            }

            var transcript = buildLLMCallTranscript(systemPrompt, runEntity.input, output);

            runEntity.status = RunStatus.COMPLETED;
            runEntity.output = output;
            runEntity.completedAt = ZonedDateTime.now();
            runEntity.tokenUsage = tokenUsage;
            runEntity.transcript = transcript;
            agentRunCollection.replace(runEntity);
        } catch (Exception e) {
            LOGGER.error("llm call run failed, runId={}", runId, e);
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

    private String executeWithTimeout(Agent agent, String input, int timeoutSeconds) throws Exception {
        var future = CompletableFuture.supplyAsync(() -> agent.run(input), executorService);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private Agent buildAgent(AgentDefinition definition) {
        var config = definition.publishedConfig;
        var toolIds = config != null ? config.toolIds : definition.toolIds;
        var tools = toolRegistryService.resolveTools(toolIds);
        var builder = Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .toolCalls(tools);

        var systemPrompt = config != null ? config.systemPrompt : definition.systemPrompt;
        var model = config != null ? config.model : definition.model;
        var temperature = config != null ? config.temperature : definition.temperature;
        var maxTurns = config != null ? config.maxTurns : definition.maxTurns;

        if (systemPrompt != null) builder.systemPrompt(systemPrompt);
        if (model != null) builder.model(model);
        if (temperature != null) builder.temperature(temperature);
        if (maxTurns != null) builder.maxTurn(maxTurns);

        var agent = builder.build();
        agent.setAuthenticated(true);
        return agent;
    }

    private void updateRunStatus(AgentRun runEntity, RunStatus status, String output, String error, Agent agent) {
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
}
