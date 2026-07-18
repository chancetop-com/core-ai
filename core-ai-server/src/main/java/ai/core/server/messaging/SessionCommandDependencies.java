package ai.core.server.messaging;

import ai.core.server.sandbox.SandboxService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;

/**
 * @author stephen
 */
public record SessionCommandDependencies(AgentSessionManager sessionManager, ChatMessageService chatMessageService,
                                         SessionOwnershipRegistry ownershipRegistry, SandboxService sandboxService,
                                         EventPublisher eventPublisher) {
}
