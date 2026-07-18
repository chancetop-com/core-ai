package ai.core.server;

import ai.core.a2a.A2AHttpPaths;
import ai.core.api.a2a.StreamResponse;
import ai.core.server.a2a.A2AAgentCardController;
import ai.core.server.a2a.A2AMessageController;
import ai.core.server.a2a.A2AStreamChannelListener;
import ai.core.server.a2a.A2ATaskController;
import ai.core.server.a2a.ServerA2AService;
import ai.core.server.sse.SseEndpointRegistry;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class A2AModule extends Module {
    @Override
    protected void initialize() {
        bind(ServerA2AService.class);

        var agentCardController = bind(A2AAgentCardController.class);
        var messageController = bind(A2AMessageController.class);
        var taskController = bind(A2ATaskController.class);

        http().route(HTTPMethod.GET, "/api/a2a/agents/:agentId/.well-known/agent-card.json", agentCardController::get);
        http().route(HTTPMethod.POST, "/api/a2a" + A2AHttpPaths.MESSAGE_SEND, messageController::send);
        http().route(HTTPMethod.POST, "/api/a2a/agents/:agentId/message/send", messageController::send);
        http().route(HTTPMethod.GET, "/api/a2a/tasks/:taskId", taskController::get);
        http().route(HTTPMethod.POST, "/api/a2a/tasks/:taskId" + A2AHttpPaths.TASK_CANCEL, taskController::cancel);

        var registry = bean(SseEndpointRegistry.class);
        registry.register(HTTPMethod.POST, "/api/a2a" + A2AHttpPaths.MESSAGE_STREAM, StreamResponse.class, bind(A2AStreamChannelListener.class), false);
    }
}
