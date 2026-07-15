package ai.core.server;

import core.framework.module.Module;

/**
 * Session infrastructure module.
 * Core session services (AgentSessionManager, ChatMessageService) are
 * bound in {@link ServerModule} due to in-module dependency ordering.
 * This module is a structural placeholder loaded by {@link ServerApp}.
 *
 * @author stephen
 */
public class SessionModule extends Module {

    @Override
    protected void initialize() {
        // Session services are bound in ServerModule.bindService() to
        // support in-module dependents (AgentDefinitionService, ServerA2AService, etc.)
    }
}
