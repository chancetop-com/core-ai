package ai.core.server.messaging;

import ai.core.api.a2a.Message;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.IdName;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.agent.ExecutionContext;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.sandbox.PendingFile;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.tool.LoadedToolRefNames;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class InProcessCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InProcessCommandHandler.class);
    private static final String RPC_CHANNEL_PREFIX = "coreai:rpc:";

    private final AgentSessionManager sessionManager;
    private final ChatMessageService chatMessageService;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final AgentDraftGenerator agentDraftGenerator;
    private final AgentDefinitionService agentDefinitionService;
    private final ServerA2AService serverA2AService;
    private final JedisPool jedisPool;
    private final SandboxService sandboxService;
    private final EventPublisher eventPublisher;
    private final ToolRegistryService toolRegistryService;
    private final ObjectStorageServiceResolver objectStorageResolver;
    private final ai.core.server.domain.SessionAttachmentRefRepository attachmentRepository;

    public InProcessCommandHandler(SessionCommandDependencies sessionDependencies, CommandRpcDependencies rpcDependencies) {
        this.sessionManager = sessionDependencies.sessionManager();
        this.chatMessageService = sessionDependencies.chatMessageService();
        this.ownershipRegistry = sessionDependencies.ownershipRegistry();
        this.agentDraftGenerator = rpcDependencies.agentDraftGenerator();
        this.agentDefinitionService = rpcDependencies.agentDefinitionService();
        this.serverA2AService = rpcDependencies.serverA2AService();
        this.jedisPool = rpcDependencies.jedisPool();
        this.sandboxService = sessionDependencies.sandboxService();
        this.eventPublisher = sessionDependencies.eventPublisher();
        this.objectStorageResolver = sessionDependencies.objectStorageResolver();
        this.attachmentRepository = sessionDependencies.attachmentRepository();
        this.toolRegistryService = rpcDependencies.toolRegistryService();
    }

    /**
     * Process a single command. Called by {@link CommandConsumer} from any thread.
     */
    public void handle(SessionCommand command) {
        LOGGER.info("processing command: type={}, sessionId={}", command.type(), command.sessionId());
        try {
            if (command.sessionId() != null && command.type() != CommandType.CLOSE_SESSION) {
                sessionManager.touchActivity(command.sessionId());
            }
            switch (command.type()) {
                case SEND_MESSAGE -> handleSendMessage(command);
                case APPROVE_TOOL -> handleApproveTool(command);
                case CANCEL_TURN -> handleCancelTurn(command);
                case CLOSE_SESSION -> handleCloseSession(command);
                case LOAD_TOOLS -> handleLoadTools(command);
                case LOAD_SKILLS -> handleLoadSkills(command);
                case UNLOAD_SKILLS -> handleUnloadSkills(command);
                case LOAD_SUB_AGENTS -> handleLoadSubAgents(command);
                case GENERATE_AGENT_DRAFT -> handleGenerateAgentDraft(command);
                case A2A_START_TASK -> handleA2AStartTask(command);
                case A2A_CANCEL_TASK -> handleA2ACancelTask(command);
                case A2A_RESUME_TASK -> handleA2AResumeTask(command);
                default -> LOGGER.warn("unknown command type: {}", command.type());
            }
        } catch (Throwable t) {
            handleCommandError(command, t);
        }
    }

    private void handleCommandError(SessionCommand command, Throwable t) {
        LOGGER.error("failed to process command, sessionId={}, type={}", command.sessionId(), command.type(), t);
        publishCommandError(command, t);
        tryRespondError(command, t.getMessage());
    }

    private void tryRespondError(SessionCommand command, String message) {
        try {
            respondError(command, message);
        } catch (Throwable ignored) {
            // best-effort: under OOM responding may also fail; do not rethrow
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSendMessage(SessionCommand command) {
        var payload = JsonUtil.fromJson(Map.class, command.payload());
        var message = (String) payload.get("message");
        var variables = (Map<String, Object>) payload.get("variables");
        if (variables == null || variables.isEmpty()) variables = null;

        LOGGER.info("handleSendMessage: looking up session sessionId={}", command.sessionId());
        var session = sessionManager.getSession(command.sessionId());

        // upload pending files carried in command payload (cross-pod safe)
        var pendingFilesRaw = (List<Map<String, Object>>) payload.get("pendingFiles");
        if (pendingFilesRaw != null && !pendingFilesRaw.isEmpty()) {
            var pendingFiles = new ArrayList<PendingFile>();
            for (var f : pendingFilesRaw) {
                pendingFiles.add(new PendingFile(
                        (String) f.get("fileName"),
                        (String) f.get("container"),
                        (String) f.get("blobName")));
            }
            LOGGER.info("handleSendMessage: uploading {} pending files from command payload", pendingFiles.size());
            sandboxService.uploadFiles(command.sessionId(), pendingFiles);
        }

        var attachedContents = multimodalAttachments(payload, command.sessionId(), command.userId());
        chatMessageService.writeUserMessage(command.sessionId(), appendVideoHints(message, attachedContents));
        LOGGER.info("handleSendMessage: sending message to agent");
        session.sendMessage(message, variables, attachedContents);
        LOGGER.info("handleSendMessage: message sent to agent");

        // Renew session ownership after a successful command (session is active)
        ownershipRegistry.renew(command.sessionId());
    }

    @SuppressWarnings("unchecked")
    private List<ExecutionContext.AttachedContent> multimodalAttachments(Map<String, Object> payload, String sessionId, String userId) {
        var attachments = (List<Map<String, Object>>) payload.get("multimodalAttachments");
        if (attachments == null || attachments.isEmpty()) {
            attachments = (List<Map<String, Object>>) payload.get("imageAttachments");
        }
        if (attachments == null || attachments.isEmpty()) return null;
        var storageService = objectStorageResolver.resolve();
        if (storageService == null) {
            throw new IllegalStateException("object storage is not configured");
        }
        var contents = new ArrayList<ExecutionContext.AttachedContent>(attachments.size());
        for (var attachment : attachments) {
            var type = (String) attachment.get("type");
            var container = (String) attachment.get("container");
            var blobName = (String) attachment.get("blobName");
            var contentType = (String) attachment.get("contentType");
            if ("VIDEO".equals(type)) {
                if (!validVideoAttachment(container, blobName)) {
                    throw new IllegalArgumentException("invalid video attachment");
                }
                var reference = new ai.core.server.domain.SessionAttachmentRef();
                reference.id = "video_" + java.util.UUID.randomUUID();
                reference.sessionId = sessionId;
                reference.userId = userId;
                reference.container = container;
                reference.blobName = blobName;
                var metadata = storageService.headObject(container, blobName);
                reference.sourceETag = metadata.etag();
                reference.sourceSizeBytes = metadata.sizeBytes();
                reference.contentType = resolveVideoContentType(metadata.contentType(), contentType);
                reference.fileName = (String) attachment.get("fileName");
                reference.createdAt = java.time.ZonedDateTime.now();
                attachmentRepository.insert(reference);
                contents.add(ExecutionContext.AttachedContent.ofReference(
                        reference.id, reference.contentType, reference.fileName));
            } else {
                if (!validImageAttachment(container, blobName, contentType)) {
                    throw new IllegalArgumentException("invalid image attachment");
                }
                var bytes = storageService.downloadObject(container, blobName);
                var content = ExecutionContext.AttachedContent.ofBase64(
                        Base64.getEncoder().encodeToString(bytes), contentType,
                        ExecutionContext.AttachedContent.AttachedContentType.IMAGE,
                        (String) attachment.get("fileName"));
                content.url = (String) attachment.get("url");
                contents.add(content);
            }
        }
        return contents;
    }

    private String appendVideoHints(String message, List<ExecutionContext.AttachedContent> attachedContents) {
        if (attachedContents == null || attachedContents.isEmpty()) return message;
        var hints = new ArrayList<String>();
        for (var content : attachedContents) {
            if (content.type != ExecutionContext.AttachedContent.AttachedContentType.VIDEO) continue;
            var name = content.filename != null ? content.filename : "video";
            hints.add("[Video attachment: " + name + "]\nreference: " + content.url);
        }
        if (hints.isEmpty()) return message;
        var text = String.join("\n", hints);
        return message == null || message.isBlank() ? text : message + "\n\n" + text;
    }

    private String resolveVideoContentType(String storageContentType, String clientContentType) {
        if (storageContentType != null && storageContentType.startsWith("video/")) return storageContentType;
        if (clientContentType != null && clientContentType.startsWith("video/")) return clientContentType;
        return "video/mp4";
    }

    private boolean validImageAttachment(String container, String blobName, String contentType) {
        return validObjectAttachment(container, blobName, contentType) && contentType.startsWith("image/");
    }

    private boolean validVideoAttachment(String container, String blobName) {
        return container != null && blobName != null
                && container.equals(objectStorageResolver.multimodalContainer())
                && blobName.startsWith("ai/");
    }

    private boolean validObjectAttachment(String container, String blobName, String contentType) {
        return container != null && blobName != null && contentType != null
                && container.equals(objectStorageResolver.multimodalContainer())
                && blobName.startsWith("ai/");
    }

    private void handleApproveTool(SessionCommand command) {
        var payload = JsonUtil.fromJson(Map.class, command.payload());
        var callId = (String) payload.get("callId");
        var decision = ApprovalDecision.valueOf((String) payload.get("decision"));

        var session = sessionManager.getSession(command.sessionId());
        session.approveToolCall(callId, decision);
        ownershipRegistry.renew(command.sessionId());
    }

    private void handleCancelTurn(SessionCommand command) {
        var session = sessionManager.getSession(command.sessionId());
        session.cancelTurn();
        publishCancelAcknowledged(command.sessionId());
    }

    private void handleCloseSession(SessionCommand command) {
        sessionManager.closeSession(command.sessionId());
    }

    // --- RPC handlers ---

    private void handleLoadTools(SessionCommand command) {
        var payload = JsonUtil.fromJson(LoadToolsPayload.class, command.payload());
        var toolRefs = payload != null ? payload.tools : null;
        if (toolRefs == null || toolRefs.isEmpty()) {
            respondOk(command, JsonUtil.toJson(Map.of("loadedTools", List.of())));
            return;
        }
        var loadedTools = sessionManager.loadToolRefs(command.sessionId(), toolRefs, command.userId());
        var idNames = LoadedToolRefNames.toIdNames(loadedTools, toolRegistryService);
        var result = JsonUtil.toJson(Map.of("loadedTools", idNames));
        respondOk(command, result);
    }

    private void publishCommandError(SessionCommand command, Throwable t) {
        if (command.requestId() != null || command.sessionId() == null || eventPublisher == null) return;
        var message = t.getMessage();
        if (message == null || message.isBlank()) message = t.getClass().getSimpleName();
        try {
            var error = new SseErrorEvent();
            error.message = message;
            error.detail = t.getClass().getName();
            eventPublisher.publish(command.sessionId(), error);

            var status = new SseStatusChangeEvent();
            status.status = SessionStatus.ERROR;
            eventPublisher.publish(command.sessionId(), status);
        } catch (Throwable publishError) {
            LOGGER.warn("failed to publish command error event, sessionId={}", command.sessionId(), publishError);
        }
    }

    private void publishCancelAcknowledged(String sessionId) {
        if (sessionId == null || eventPublisher == null) return;
        try {
            var status = new SseStatusChangeEvent();
            status.status = SessionStatus.IDLE;
            eventPublisher.publish(sessionId, status);
        } catch (Throwable publishError) {
            LOGGER.warn("failed to publish cancel acknowledgement, sessionId={}", sessionId, publishError);
        }
    }

    private void handleLoadSkills(SessionCommand command) {
        var payload = JsonUtil.fromJson(LoadSkillsPayload.class, command.payload());
        var skillIds = IdLists.clean(payload != null ? payload.skillIds : null);
        var names = sessionManager.loadSkills(command.sessionId(), skillIds, command.userId());
        var idNames = new ArrayList<IdName>(skillIds.size());
        for (int i = 0; i < skillIds.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = skillIds.get(i);
            v.name = names.get(i);
            idNames.add(v);
        }
        var result = JsonUtil.toJson(Map.of("loadedSkills", idNames));
        respondOk(command, result);
    }

    private void handleUnloadSkills(SessionCommand command) {
        var payload = JsonUtil.fromJson(UnloadSkillsPayload.class, command.payload());
        var skillIds = IdLists.clean(payload != null ? payload.skillIds : null);
        if (skillIds.isEmpty()) {
            var remaining = sessionManager.unloadSkills(command.sessionId(), List.of(), command.userId());
            respondOk(command, JsonUtil.toJson(Map.of("remainingSkills", remaining)));
            return;
        }
        var remaining = sessionManager.unloadSkills(command.sessionId(), skillIds, command.userId());
        var result = JsonUtil.toJson(Map.of("remainingSkills", remaining));
        respondOk(command, result);
    }

    private void handleLoadSubAgents(SessionCommand command) {
        var payload = JsonUtil.fromJson(LoadSubAgentsPayload.class, command.payload());
        var agentIds = IdLists.clean(payload != null ? payload.agentIds : null);
        if (agentIds.isEmpty()) {
            respondOk(command, JsonUtil.toJson(Map.of("loadedSubAgents", List.of())));
            return;
        }
        var definitions = agentIds.stream()
                .map(agentDefinitionService::getEntity)
                .toList();
        var names = sessionManager.loadSubAgents(command.sessionId(), definitions, command.userId());
        var idNames = new ArrayList<IdName>(definitions.size());
        for (int i = 0; i < definitions.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = definitions.get(i).id;
            v.name = names.get(i);
            idNames.add(v);
        }
        var result = JsonUtil.toJson(Map.of("loadedSubAgents", idNames));
        respondOk(command, result);
    }

    private void handleGenerateAgentDraft(SessionCommand command) {
        var session = sessionManager.getSession(command.sessionId());
        var draft = agentDraftGenerator.generate(session);
        var result = JsonUtil.toJson(draft);
        respondOk(command, result);
    }

    private void handleA2AStartTask(SessionCommand command) {
        var payload = JsonUtil.fromJson(ai.core.server.a2a.A2AStartTaskCommandPayload.class, command.payload());
        var task = serverA2AService.startTaskOnOwner(payload, command.userId());
        respondOk(command, JsonUtil.toJson(task));
    }

    private void handleA2ACancelTask(SessionCommand command) {
        var payload = JsonUtil.fromJson(Map.class, command.payload());
        var taskId = (String) payload.get("taskId");
        var task = serverA2AService.cancelTaskOnOwner(taskId);
        respondOk(command, JsonUtil.toJson(task));
    }

    private void handleA2AResumeTask(SessionCommand command) {
        var message = JsonUtil.fromJson(Message.class, command.payload());
        var task = serverA2AService.resumeTaskOnOwner(message, command.userId());
        respondOk(command, JsonUtil.toJson(task));
    }

    // --- RPC response helpers ---

    private void respondOk(SessionCommand command, String payloadJson) {
        var requestId = command.requestId();
        if (requestId == null) return;  // not an RPC command, no response needed
        var response = RpcClient.okResponse(payloadJson);
        publishResponse(requestId, response);
    }

    private void respondError(SessionCommand command, String message) {
        var requestId = command.requestId();
        if (requestId == null) return;
        var response = RpcClient.errorResponse(message);
        publishResponse(requestId, response);
    }

    private void publishResponse(String requestId, String json) {
        try (var jedis = jedisPool.getResource()) {
            jedis.publish(RPC_CHANNEL_PREFIX + requestId, json);
        }
    }

    // --- Payload DTOs (internal) ---

    @SuppressWarnings("unused")
    private record LoadToolsPayload(List<ai.core.server.domain.ToolRef> tools) {
    }

    @SuppressWarnings("unused")
    private record LoadSkillsPayload(List<String> skillIds) {
    }

    @SuppressWarnings("unused")
    private record UnloadSkillsPayload(List<String> skillIds) {
    }

    @SuppressWarnings("unused")
    private record LoadSubAgentsPayload(List<String> agentIds) {
    }
}
