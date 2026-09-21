package ai.core.server.sandboxhub;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.sandboxhub.SandboxHubCallResponse;
import ai.core.api.server.sandboxhub.SandboxHubContentPart;
import ai.core.api.server.sandboxhub.SandboxHubLlmUsage;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.session.AgentSessionManager;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCallResult;
import ai.core.tool.async.AsyncToolTaskExecutor;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Serves the sandbox hub surface: the tool set of the agent that owns the calling sandbox, so a
 * script inside that sandbox can use the session's own capabilities without ever holding a
 * credential.
 * <p>
 * Every call is executed through the owning agent's tool executor, which makes a hub call
 * indistinguishable from the same call the model would make in a turn: caller identity for
 * outbound requests, tracing, token accounting, quota and async-task registration all follow the
 * session. The hub adds no business logic of its own — it resolves the tool, waits, and maps the
 * result onto the sandbox contract.
 * <p>
 * Calls that outlive the caller's wait are handed back as {@code pending} plus a {@code task_id};
 * the caller polls {@code /tasks/:task_id} and the row stays open in {@code hub_calls} until the
 * work really finishes.
 *
 * @author xander
 */
public class SandboxHubService {
    public static final String CONTRACT_VERSION = "1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxHubService.class);
    private static final String SOURCE = "sandbox";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_FAILED = "failed";
    private static final String EMPTY_ARGUMENTS = "{}";
    private static final String FUNCTION_TYPE = "function";
    private static final String SUBAGENT_TOKEN_USAGE = "subagent_token_usage";
    private static final String LLM_CALL_INPUT_TOKENS = "llm_call_input_tokens";
    private static final String LLM_CALL_OUTPUT_TOKENS = "llm_call_output_tokens";
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = (int) (SandboxConstants.MAX_TOOL_TIMEOUT_MS / 1000);
    private static final long TASK_RETENTION_MILLIS = 600_000L;

    private static List<SandboxHubContentPart> content(ToolCallResult result) {
        if (!result.hasImage()) return List.of();
        var part = new SandboxHubContentPart();
        part.type = "image";
        part.mimeType = result.getImageFormat() == null ? "image/png" : "image/" + result.getImageFormat();
        part.data = result.getImageBase64();
        return List.of(part);
    }

    private static SandboxHubLlmUsage llmUsage(ToolCallResult result) {
        var usage = result.getLlmUsage();
        if (usage == null) usage = statsUsage(result);
        if (usage == null && result.getLlmModel() == null) return null;
        var view = new SandboxHubLlmUsage();
        view.model = result.getLlmModel();
        if (usage != null) {
            view.inputTokens = (long) usage.getPromptTokens();
            view.outputTokens = (long) usage.getCompletionTokens();
        }
        view.cost = result.getLlmCostUsd();
        return view;
    }

    // llm_call and sub-agent tools report their tokens as stats instead of setting the usage the
    // executor consumes, so the hub reads both shapes: the script still sees what the call cost.
    private static Usage statsUsage(ToolCallResult result) {
        var stats = result.getStats();
        if (stats == null || stats.isEmpty()) return null;
        if (stats.get(SUBAGENT_TOKEN_USAGE) instanceof Usage usage) return usage;
        var input = number(stats.get(LLM_CALL_INPUT_TOKENS));
        var output = number(stats.get(LLM_CALL_OUTPUT_TOKENS));
        if (input == null || output == null) return null;
        return new Usage(input.intValue(), output.intValue(), input.intValue() + output.intValue());
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static SandboxHubCatalog catalogOf(InProcessAgentSession agentSession) {
        return SandboxHubCatalog.of(agentSession.agent().getExecutionContext());
    }

    private static SandboxHubCatalog.Entry find(SandboxHubCatalog catalog, String name) {
        var entry = catalog.find(name);
        if (entry == null) {
            throw new NotFoundException("tool not found in this session: " + name + ", available: " + catalog.entries().size() + " tools");
        }
        if (!entry.callable()) {
            throw new BadRequestException("tool is not callable in a script: " + name);
        }
        return entry;
    }

    private static String causeMessage(ExecutionException e) {
        var cause = e.getCause();
        return cause == null ? e.getMessage() : cause.getMessage();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Inject
    AgentSessionManager sessionManager;
    @Inject
    HubCallAuditService auditService;

    private final Map<String, InFlight> tasks = new ConcurrentHashMap<>();

    /**
     * The session's tool set as the owning replica sees it. Reads (me / catalog / search / describe) are
     * answered from this, which is why they work from any pod: the owner builds it, the caller renders it.
     */
    public SandboxHubCatalogSnapshot snapshot(SandboxHubSession session) {
        var agentSession = agentSession(session);
        var catalog = catalogOf(agentSession);
        return SandboxHubCatalogSnapshot.of(agentSession.agent().getName(), catalog.details(), catalog.datasets());
    }

    public SandboxHubCallResponse call(SandboxHubSession session, String name, HubCallRequest request) {
        var agentSession = agentSession(session);
        var agent = agentSession.agent();
        var context = agent.getExecutionContext();
        var entry = find(SandboxHubCatalog.of(context), name);
        var arguments = request != null && hasText(request.arguments) ? request.arguments : EMPTY_ARGUMENTS;
        var timeoutSeconds = timeoutSeconds(entry, request);
        var callId = auditService.begin(new HubCallAuditService.BeginRequest(UUID.randomUUID().toString(),
                HubCallAuditService.KIND_SANDBOX_TOOL, session.userId(), null, SOURCE, entry.refId(), entry.refId(),
                session.sessionId(), entry.name(), arguments, session.sandboxId(), entry.kind(), session.sessionId()));

        trimTasks();
        var future = CompletableFuture.supplyAsync(() -> execute(agent, entry, arguments, context, callId),
                AsyncToolTaskExecutor.getInstance().getExecutor());
        tasks.put(callId, new InFlight(future, System.currentTimeMillis() + TASK_RETENTION_MILLIS));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return pending(callId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failure(callId, "hub call interrupted", 0L);
        } catch (ExecutionException e) {
            return failure(callId, causeMessage(e), 0L);
        }
    }

    /**
     * Polls a call that outlived its wait. Also serves tasks the tools own themselves (async media or
     * workflow jobs): the task id is looked up in the session's async task registry and polled
     * through the tool that created it.
     */
    public SandboxHubCallResponse task(SandboxHubSession session, String taskId) {
        trimTasks();
        var inFlight = tasks.get(taskId);
        if (inFlight != null) {
            if (!inFlight.future().isDone()) return pending(taskId);
            return resultOf(inFlight.future());
        }
        return pollToolTask(agentSession(session), taskId);
    }

    private SandboxHubCallResponse pollToolTask(InProcessAgentSession agentSession, String taskId) {
        var context = agentSession.agent().getExecutionContext();
        var manager = context.getAsyncTaskManager();
        var task = manager == null ? null : manager.loadTask(taskId).orElse(null);
        if (task == null) throw new NotFoundException("unknown task: " + taskId);
        return response(task.tool().poll(taskId), taskId, 0L);
    }

    private SandboxHubCallResponse resultOf(CompletableFuture<SandboxHubCallResponse> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(causeMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling task", e);
        }
    }

    private SandboxHubCallResponse execute(Agent agent, SandboxHubCatalog.Entry entry, String arguments,
                                           ExecutionContext context, String callId) {
        var startedAt = System.currentTimeMillis();
        ToolCallResult result;
        try {
            var functionCall = FunctionCall.of(callId, FUNCTION_TYPE, entry.tool().getName(), arguments);
            result = agent.executeToolOutsideTurn(entry.tool(), functionCall, context);
        } catch (RuntimeException e) {
            LOGGER.warn("sandbox hub call failed, tool={}, call={}", entry.name(), callId, e);
            var durationMs = System.currentTimeMillis() - startedAt;
            auditService.finish(callId, durationMs, null, false, null, e.getMessage());
            return failure(callId, e.getMessage(), durationMs);
        }
        var response = response(result, callId, System.currentTimeMillis() - startedAt);
        auditService.finish(callId, response.durationMs, response.text, !Boolean.TRUE.equals(response.isError), null, response.errorMessage);
        return response;
    }

    private SandboxHubCallResponse response(ToolCallResult result, String callId, long durationMs) {
        var response = new SandboxHubCallResponse();
        response.callId = callId;
        response.durationMs = durationMs;
        response.text = result.getResult();
        response.content = content(result);
        response.llmUsage = llmUsage(result);
        if (result.isPending() || result.isWaitingForInput() || result.isAsyncLaunched()) {
            response.status = STATUS_PENDING;
            response.taskId = result.getTaskId();
            return response;
        }
        var failed = result.isFailed();
        response.status = failed ? STATUS_FAILED : STATUS_COMPLETED;
        response.success = !failed;
        response.isError = failed;
        if (failed) response.errorMessage = result.getResult();
        recordLlmUsage(callId, response.llmUsage);
        return response;
    }

    private SandboxHubCallResponse pending(String taskId) {
        var response = new SandboxHubCallResponse();
        response.callId = taskId;
        response.taskId = taskId;
        response.status = STATUS_PENDING;
        response.durationMs = 0L;
        response.content = List.of();
        return response;
    }

    private SandboxHubCallResponse failure(String callId, String message, long durationMs) {
        var response = new SandboxHubCallResponse();
        response.callId = callId;
        response.status = STATUS_FAILED;
        response.success = Boolean.FALSE;
        response.isError = Boolean.TRUE;
        response.errorCode = "tool_failed";
        response.text = message;
        response.errorMessage = message;
        response.content = List.of();
        response.durationMs = durationMs;
        return response;
    }

    private void recordLlmUsage(String callId, SandboxHubLlmUsage usage) {
        if (usage == null) return;
        auditService.attachRun(callId, null, null, usage.inputTokens, usage.outputTokens);
    }

    private InProcessAgentSession agentSession(SandboxHubSession session) {
        var agentSession = sessionManager.getSession(session.sessionId());
        if (agentSession == null) {
            throw new NotFoundException("session is not available on this server: " + session.sessionId());
        }
        return agentSession;
    }

    private int timeoutSeconds(SandboxHubCatalog.Entry entry, HubCallRequest request) {
        if (request == null || request.timeoutSeconds == null) return SandboxHubCatalog.defaultTimeoutSeconds(entry);
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(request.timeoutSeconds, MAX_TIMEOUT_SECONDS));
    }

    private void trimTasks() {
        var now = System.currentTimeMillis();
        tasks.entrySet().removeIf(task -> task.getValue().expiresAt() < now);
    }

    /** Identity of the calling sandbox, taken from the session token — never from the request body or query. */
    public record SandboxHubSession(String sessionId, String userId, String sandboxId, String expiresAt) {
    }

    private record InFlight(CompletableFuture<SandboxHubCallResponse> future, long expiresAt) {
    }
}
