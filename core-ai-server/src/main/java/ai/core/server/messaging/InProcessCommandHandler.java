package ai.core.server.messaging;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * @author stephen
 */
public class InProcessCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InProcessCommandHandler.class);

    private final AgentSessionManager sessionManager;
    private final ChatMessageService chatMessageService;
    private final SessionOwnershipRegistry ownershipRegistry;

    public InProcessCommandHandler(AgentSessionManager sessionManager,
                                   ChatMessageService chatMessageService,
                                   SessionOwnershipRegistry ownershipRegistry) {
        this.sessionManager = sessionManager;
        this.chatMessageService = chatMessageService;
        this.ownershipRegistry = ownershipRegistry;
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
                default -> LOGGER.warn("unknown command type: {}", command.type());
            }
        } catch (Exception e) {
            LOGGER.warn("failed to process command, sessionId={}, type={}", command.sessionId(), command.type(), e);
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
}
