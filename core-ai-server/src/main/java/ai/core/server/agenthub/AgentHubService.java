package ai.core.server.agenthub;

import ai.core.api.a2a.Message;
import ai.core.api.a2a.Part;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.agenthub.AgentHubDetail;
import ai.core.api.server.agenthub.AgentHubInputRequest;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.agenthub.AgentHubRunResult;
import ai.core.api.server.agenthub.AgentHubSummary;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.server.a2a.A2ATaskView;
import ai.core.server.a2a.ServerA2ACallerService;
import ai.core.server.a2a.ServerA2ATaskOptions;
import ai.core.server.agent.AgentCallAccessPolicy;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.run.AgentRunService;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Execution and listing behind the Agent Hub, layered over the agent catalog, the A2A task
 * machinery and the stateless LLM call runner:
 * <ul>
 *     <li>an AGENT runs as a server session — {@code context_id} continues it, an
 *     {@code input_required} task is answered with a tool approval;</li>
 *     <li>an LLM_CALL runs through {@link AgentRunService#llmCall} and is stateless (no context);</li>
 *     <li>a run that outlives the caller's wait is reported as {@code running} and keeps going.</li>
 * </ul>
 * Only capability summaries leave this class: never system prompts, models or tool details.
 *
 * @author stephen
 */
public class AgentHubService {
    private static final int DEFAULT_WAIT_SECONDS = 120;
    private static final int MAX_WAIT_SECONDS = 300;
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 20000;
    private static final int MAX_TASK_CHARS = 30000;
    private static final String STATUS_RUNNING = "running";
    private static final String ERROR_INPUT_TOO_LONG = "input_too_long";
    private static final String ERROR_AMBIGUOUS_NAME = "ambiguous_name";
    private static final String ERROR_AGENT_FAILED = "agent_failed";
    private static final String SOURCE_SERVER = "server";
    private static final String SOURCE_HUB = "hub";
    private static final String USER_TYPE_API = "api";
    private static final String USER_TYPE_INTERNAL = "internal";

    @Inject
    AgentCatalogService catalog;
    @Inject
    AgentCallAccessPolicy accessPolicy;
    @Inject
    ServerA2ACallerService callerService;
    @Inject
    AgentRunService agentRunService;
    @Inject
    HubCallAuditService auditService;

    public List<AgentHubSummary> search(String userId, String query, String type, String source, Integer limit) {
        var agents = catalog.search(userId, query, type, source, limit);
        var result = new ArrayList<AgentHubSummary>();
        for (var agent : agents) {
            if (!accessPolicy.canAccess(userId, agent.id())) continue;
            result.add(summary(agent, userId));
        }
        return result;
    }

    public List<AgentHubSummary> lookup(String userId, String name) {
        var result = new ArrayList<AgentHubSummary>();
        for (var agent : catalog.lookup(userId, name)) {
            if (!accessPolicy.canAccess(userId, agent.id())) continue;
            result.add(summary(agent, userId));
        }
        return result;
    }

    /** Exactly one visible agent for a name, or a 409 listing the candidates the caller has to disambiguate by id. */
    public AgentCatalogService.CatalogAgent resolveUnique(String userId, String name) {
        var candidates = lookup(userId, name);
        if (candidates.isEmpty()) {
            throw new NotFoundException("agent not found, name=" + name);
        }
        if (candidates.size() > 1) {
            var ids = new ArrayList<String>();
            for (var candidate : candidates) {
                ids.add(candidate.id);
            }
            throw new ConflictException(ERROR_AMBIGUOUS_NAME + ": " + ids);
        }
        return resolve(userId, candidates.get(0).id);
    }

    /** The visible and runnable agent behind an id, or 404 — the entry point of a run by id. */
    public AgentCatalogService.CatalogAgent resolve(String userId, String id) {
        var agent = catalog.find(userId, id);
        if (agent == null || !accessPolicy.canAccess(userId, agent.id())) {
            throw new NotFoundException("agent not found, id=" + id);
        }
        return agent;
    }

    public AgentHubDetail get(String userId, String id) {
        var agent = resolve(userId, id);
        var capability = catalog.capabilityFor(agent, userId);
        var summary = summary(agent, userId, capability);
        var detail = new AgentHubDetail();
        detail.id = summary.id;
        detail.name = summary.name;
        detail.description = summary.description;
        detail.type = summary.type;
        detail.source = summary.source;
        detail.status = summary.status;
        detail.publishedAt = summary.publishedAt;
        detail.ownerIsMe = summary.ownerIsMe;
        detail.systemDefault = summary.systemDefault;
        detail.toolCount = summary.toolCount;
        detail.skillNames = summary.skillNames;
        detail.subAgentNames = summary.subAgentNames;
        detail.hasSandbox = summary.hasSandbox;
        detail.inputHint = capability.inputHint();
        detail.responseSchema = agent.definition().type == DefinitionType.LLM_CALL ? capability.responseSchema() : null;
        return detail;
    }

    public AgentHubRunResult run(String userId, AgentCatalogService.CatalogAgent agent, AgentHubRunRequest request) {
        var task = request != null ? request.task : null;
        if (task == null || task.isBlank()) throw new BadRequestException("task required");
        if (task.length() > MAX_TASK_CHARS) {
            throw new BadRequestException("task is too long, limit=" + MAX_TASK_CHARS, ERROR_INPUT_TOO_LONG);
        }
        accessPolicy.checkCanRun(userId, agent.id());
        return audited(userId, agent, task, () -> agent.definition().type == DefinitionType.LLM_CALL
                ? llmCall(userId, agent, request)
                : a2aRun(userId, agent, request));
    }

    public AgentHubRunResult status(String userId, String taskId) {
        return result(callerService.viewTask(taskId, userId), null, null);
    }

    public AgentHubRunResult reply(String userId, String taskId, AgentHubReplyRequest request) {
        var message = request != null && request.message != null && !request.message.isBlank() ? request.message : null;
        var decision = request != null && request.decision != null && !request.decision.isBlank() ? request.decision : null;
        if (message == null && decision == null) throw new BadRequestException("decision or message required");
        return auditedResume(userId, message != null ? message : decision,
                () -> result(callerService.replyForCaller(taskId, userId, decision, message, Duration.ofSeconds(DEFAULT_WAIT_SECONDS)), null, null));
    }

    public AgentHubRunResult cancel(String userId, String taskId) {
        return result(callerService.cancelForCaller(taskId, userId), null, null);
    }

    /**
     * Writes the audit row around one agent execution: the row exists before the call (so a hang or a
     * crash still leaves a trace) and carries the task/context/token coordinates when it completes.
     */
    private AgentHubRunResult audited(String userId, AgentCatalogService.CatalogAgent agent, String task,
                                      Supplier<AgentHubRunResult> execution) {
        String auditId = auditService.begin(new HubCallAuditService.BeginRequest(UUID.randomUUID().toString(),
                HubCallAuditService.KIND_AGENT, userId, accessPolicy.isApiUser(userId) ? USER_TYPE_API : USER_TYPE_INTERNAL,
                SOURCE_HUB, agent.id(), agent.definition().id, null, agent.name(), task));
        return execute(auditId, execution);
    }

    private AgentHubRunResult auditedResume(String userId, String arguments,
                                            Supplier<AgentHubRunResult> execution) {
        String auditId = auditService.begin(new HubCallAuditService.BeginRequest(UUID.randomUUID().toString(),
                HubCallAuditService.KIND_AGENT, userId, accessPolicy.isApiUser(userId) ? USER_TYPE_API : USER_TYPE_INTERNAL,
                SOURCE_HUB, null, null, null, null, arguments));
        return execute(auditId, execution);
    }

    private AgentHubRunResult execute(String auditId, Supplier<AgentHubRunResult> execution) {
        var watch = new StopWatch();
        try {
            var run = execution.get();
            boolean success = !"failed".equals(run.status) && !"cancelled".equals(run.status);
            auditService.finish(auditId, watch.elapsed(), run.output, success, null, run.errorMessage);
            auditService.attachRun(auditId, run.taskId, run.contextId, token(run.tokenUsage, "input"), token(run.tokenUsage, "output"));
            return run;
        } catch (RuntimeException e) {
            auditService.finish(auditId, watch.elapsed(), null, false, null, e.getMessage());
            throw e;
        }
    }

    private Long token(Map<String, Long> usage, String key) {
        return usage == null ? null : usage.get(key);
    }

    private AgentHubRunResult llmCall(String userId, AgentCatalogService.CatalogAgent agent, AgentHubRunRequest request) {
        var llmRequest = new LLMCallRequest();
        llmRequest.input = request.task;
        llmRequest.attachments = attachments(request.attachments);
        var response = agentRunService.llmCall(agent.id(), llmRequest, userId);
        var result = new AgentHubRunResult();
        result.agentId = agent.id();
        result.status = "completed";
        result.output = truncate(response.output, request.maxOutputChars, result);
        result.tokenUsage = response.tokenUsage;
        result.durationMs = null;
        return result;
    }

    private List<LLMCallRequest.Attachment> attachments(List<AgentHubAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        var result = new ArrayList<LLMCallRequest.Attachment>();
        for (var attachment : attachments) {
            if (attachment == null || attachment.url == null || attachment.url.isBlank()) {
                throw new BadRequestException("attachment url required");
            }
            var converted = new LLMCallRequest.Attachment();
            converted.url = attachment.url;
            converted.type = attachmentType(attachment.type);
            result.add(converted);
        }
        return result;
    }

    private LLMCallRequest.AttachmentType attachmentType(String type) {
        if (type == null || type.isBlank()) return LLMCallRequest.AttachmentType.FILE;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "image" -> LLMCallRequest.AttachmentType.IMAGE;
            case "pdf" -> LLMCallRequest.AttachmentType.PDF;
            case "video" -> LLMCallRequest.AttachmentType.VIDEO;
            default -> LLMCallRequest.AttachmentType.FILE;
        };
    }

    private AgentHubRunResult a2aRun(String userId, AgentCatalogService.CatalogAgent agent, AgentHubRunRequest request) {
        var options = Boolean.TRUE.equals(request.detach)
                ? ServerA2ATaskOptions.detached().source(SOURCE_HUB)
                : ServerA2ATaskOptions.wait(Duration.ofSeconds(waitSeconds(request.timeoutSeconds))).source(SOURCE_HUB);
        var view = callerService.sendForCaller(agent.id(), sendRequest(request), userId, options);
        return result(view, request.maxOutputChars, agent.id());
    }

    private SendMessageRequest sendRequest(AgentHubRunRequest request) {
        var message = new Message();
        message.role = "ROLE_USER";
        var parts = new ArrayList<Part>();
        parts.add(Part.text(request.task));
        if (request.attachments != null) {
            for (var attachment : request.attachments) {
                parts.add(toPart(attachment));
            }
        }
        message.parts = parts;
        message.contextId = request.contextId != null && !request.contextId.isBlank() ? request.contextId : null;
        var send = new SendMessageRequest();
        send.message = message;
        return send;
    }

    private Part toPart(AgentHubAttachment attachment) {
        if (attachment == null || attachment.url == null || attachment.url.isBlank()) {
            throw new BadRequestException("attachment url required");
        }
        return Part.file(null, mediaType(attachment.type), attachment.url);
    }

    private String mediaType(String type) {
        if (type == null || type.isBlank()) return "application/octet-stream";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "image" -> "image/*";
            case "pdf" -> "application/pdf";
            case "text" -> "text/plain";
            case "audio" -> "audio/*";
            case "video" -> "video/*";
            default -> "application/octet-stream";
        };
    }

    private int waitSeconds(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) return DEFAULT_WAIT_SECONDS;
        return Math.min(timeoutSeconds, MAX_WAIT_SECONDS);
    }

    private AgentHubRunResult result(A2ATaskView view, Integer maxOutputChars, String agentId) {
        var task = view.task();
        var result = new AgentHubRunResult();
        result.taskId = task != null ? task.id : null;
        result.contextId = view.contextId() != null ? view.contextId() : contextId(task);
        result.agentId = agentId;
        result.status = statusOf(view);
        result.output = truncate(outputOf(task), maxOutputChars, result);
        result.tokenUsage = tokenUsage(view.inputTokens(), view.outputTokens());
        result.errorCode = view.errorMessage() != null ? ERROR_AGENT_FAILED : null;
        result.errorMessage = view.errorMessage();
        if (TaskState.INPUT_REQUIRED == stateOf(view)) {
            result.inputRequest = inputRequest(view);
        }
        return result;
    }

    private AgentHubInputRequest inputRequest(A2ATaskView view) {
        var input = new AgentHubInputRequest();
        input.callId = view.awaitCallId();
        input.tool = view.awaitTool();
        input.arguments = view.awaitArguments();
        input.message = view.awaitTool() != null ? "Tool requires approval: " + view.awaitTool() : "Input required";
        return input;
    }

    private String statusOf(AgentDefinition definition) {
        if (definition.status == null) return null;
        return definition.status.name().toLowerCase(Locale.ROOT);
    }

    private String statusOf(A2ATaskView view) {
        var state = stateOf(view);
        if (state == null) return STATUS_RUNNING;
        return switch (state) {
            case COMPLETED -> "completed";
            case INPUT_REQUIRED -> "input_required";
            case FAILED, REJECTED -> "failed";
            case CANCELED -> "cancelled";
            default -> STATUS_RUNNING;
        };
    }

    private TaskState stateOf(A2ATaskView view) {
        var task = view.task();
        if (task == null || task.status == null) return null;
        return task.status.state;
    }

    private String contextId(Task task) {
        return task != null ? task.contextId : null;
    }

    private String outputOf(Task task) {
        if (task == null || task.artifacts == null) return null;
        var text = new StringBuilder();
        for (var artifact : task.artifacts) {
            if (artifact.parts == null) continue;
            for (var part : artifact.parts) {
                if (part.text == null) continue;
                if (!text.isEmpty()) text.append('\n');
                text.append(part.text);
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private String truncate(String output, Integer maxOutputChars, AgentHubRunResult result) {
        if (output == null) return null;
        int limit = maxOutputChars != null && maxOutputChars > 0 ? maxOutputChars : DEFAULT_MAX_OUTPUT_CHARS;
        if (output.length() <= limit) {
            result.outputTruncated = Boolean.FALSE;
            return output;
        }
        result.outputTruncated = Boolean.TRUE;
        return output.substring(0, limit);
    }

    private Map<String, Long> tokenUsage(Long inputTokens, Long outputTokens) {
        if (inputTokens == null && outputTokens == null) return null;
        Map<String, Long> usage = new LinkedHashMap<>();
        if (inputTokens != null) usage.put("input", inputTokens);
        if (outputTokens != null) usage.put("output", outputTokens);
        return usage;
    }

    private AgentHubSummary summary(AgentCatalogService.CatalogAgent agent, String userId) {
        return summary(agent, userId, catalog.capabilityFor(agent, userId));
    }

    private AgentHubSummary summary(AgentCatalogService.CatalogAgent agent, String userId,
                                    AgentCatalogService.Capability capability) {
        var summary = new AgentHubSummary();
        var definition = agent.definition();
        summary.id = definition.id;
        summary.name = definition.name;
        summary.description = definition.description;
        summary.type = definition.type.name().toLowerCase(Locale.ROOT);
        summary.source = SOURCE_SERVER;
        summary.status = statusOf(definition);
        summary.publishedAt = definition.publishedAt;
        summary.ownerIsMe = isOwner(definition, userId);
        summary.systemDefault = Boolean.TRUE.equals(definition.systemDefault);
        summary.toolCount = capability.toolCount();
        summary.skillNames = capability.skillNames();
        summary.subAgentNames = capability.subAgentNames();
        summary.hasSandbox = capability.hasSandbox();
        return summary;
    }

    private boolean isOwner(AgentDefinition definition, String userId) {
        return userId != null && userId.equals(definition.userId);
    }
}
