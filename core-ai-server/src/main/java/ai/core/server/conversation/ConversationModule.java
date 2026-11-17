package ai.core.server.conversation;

import ai.core.api.server.conversation.ConversationWebService;
import ai.core.server.web.ConversationWebServiceImpl;
import core.framework.module.Module;

/**
 * Direct desktop conversation facade. Loaded after AgentHubModule and MessagingRuntimeModule:
 * the service injects AgentHubService, CommandPublisher and the session runtime, and core-ng
 * resolves dependencies eagerly while binding.
 *
 * @author core-ai
 */
public class ConversationModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ConversationRequestStore.class, bind(PgConversationRequestStore.class));
        } else {
            bind(ConversationRequestStore.class, new InMemoryConversationRequestStore());
        }
        var service = bind(ConversationWebServiceImpl.class);
        service.allowedAgents = property("sys.conversation.agents").orElse("");
        api().service(ConversationWebService.class, service);
    }
}
