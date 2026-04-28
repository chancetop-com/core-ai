package ai.core.server.messaging;

import ai.core.api.server.agent.GenerateAgentDraftResponse;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

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
    private final JedisPool jedisPool;

    public InProcessCommandHandler(AgentSessionManager sessionManager,
                                   ChatMessageService chatMessageService,
                                   SessionOwnershipRegistry ownershipRegistry,
                                   AgentDraftGenerator agentDraftGenerator,
                                   AgentDefinitionService agentDefinitionService,
                                   JedisPool jedisPool) {
        this.sessionManager = sessionManager;
        this.chatMessageService = chatMessageService;
        this.ownershipRegistry = ownershipRegistry;
        this.agentDraftGenerator = agentDraftGenerator;
        this.agentDefinitionService = agentDefinitionService;
        this.jedisPool = jedisPool;
    }

    /**
     * Process a single command. Called by {@link CommandConsumer} from any thread.
     */
    public void handle(SessionCommand command) {
        LOGGER.info("processing command: type={}, sessionId={}", command.type(), command.sessionId());
        try {
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
                default -> LOGGER.warn("unknown command type: {}", command.type());
            }
        } catch (Exception e) {
            LOGGER.warn("failed to process command, sessionId={}, type={}", command.sessionId(), command.type(), e);
            respondError(command, e.getMessage());
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
        LOGGER.info("handleSendMessage: session found, writing user message");
        chatMessageService.writeUserMessage(command.sessionId(), message);
        LOGGER.info("handleSendMessage: sending message to agent");
        session.sendMessage(message, variables);
        LOGGER.info("handleSendMessage: message sent to agent");

        // Renew session ownership after a successful command (session is active)
        ownershipRegistry.renew(command.sessionId());
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
        var loadedTools = sessionManager.loadToolRefs(command.sessionId(), toolRefs);
        var result = JsonUtil.toJson(Map.of("loadedTools", loadedTools));
        respondOk(command, result);
    }

    private void handleLoadSkills(SessionCommand command) {
        var payload = JsonUtil.fromJson(LoadSkillsPayload.class, command.payload());
        var skillIds = payload != null ? payload.skillIds : null;
        if (skillIds == null || skillIds.isEmpty()) {
            respondOk(command, JsonUtil.toJson(Map.of("loadedSkills", List.of())));
            return;
        }
        var loadedSkills = sessionManager.loadSkills(command.sessionId(), skillIds);
        var result = JsonUtil.toJson(Map.of("loadedSkills", loadedSkills));
        respondOk(command, result);
    }

    private void handleUnloadSkills(SessionCommand command) {
        var payload = JsonUtil.fromJson(UnloadSkillsPayload.class, command.payload());
        var skillIds = payload != null ? payload.skillIds : null;
        if (skillIds == null || skillIds.isEmpty()) {
            var remaining = sessionManager.unloadSkills(command.sessionId(), List.of());
            respondOk(command, JsonUtil.toJson(Map.of("remainingSkills", remaining)));
            return;
        }
        var remaining = sessionManager.unloadSkills(command.sessionId(), skillIds);
        var result = JsonUtil.toJson(Map.of("remainingSkills", remaining));
        respondOk(command, result);
    }

    private void handleLoadSubAgents(SessionCommand command) {
        var payload = JsonUtil.fromJson(LoadSubAgentsPayload.class, command.payload());
        var agentIds = payload != null ? payload.agentIds : null;
        if (agentIds == null || agentIds.isEmpty()) {
            respondOk(command, JsonUtil.toJson(Map.of("loadedSubAgents", List.of())));
            return;
        }
        var definitions = agentIds.stream()
                .map(agentDefinitionService::getEntity)
                .toList();
        var loadedSubAgents = sessionManager.loadSubAgents(command.sessionId(), definitions);
        var result = JsonUtil.toJson(Map.of("loadedSubAgents", loadedSubAgents));
        respondOk(command, result);
    }

    private void handleGenerateAgentDraft(SessionCommand command) {
        var session = sessionManager.getSession(command.sessionId());
        var draft = agentDraftGenerator.generate(session);
        var result = JsonUtil.toJson(draft);
        respondOk(command, result);
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
