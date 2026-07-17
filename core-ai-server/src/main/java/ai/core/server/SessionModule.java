package ai.core.server;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.web.ChatSessionController;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.LiteLLMProxyChannelListener;
import ai.core.server.web.sse.SseAuthInterceptor;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class SessionModule extends Module {
    @Override
    protected void initialize() {
        bindSessionRuntime();
        registerSseEndpoints();
        schedule().fixedRate("idle-session-cleanup", bind(IdleSessionCleanupJob.class), Duration.ofMinutes(5));

        var chatSessionController = bind(ChatSessionController.class);
        http().route(HTTPMethod.GET, "/api/chat/sessions", chatSessionController::list);
        http().route(HTTPMethod.GET, "/api/chat/sessions/:sessionId", chatSessionController::get);
        http().route(HTTPMethod.DELETE, "/api/chat/sessions/:sessionId", chatSessionController::delete);
        http().route(HTTPMethod.POST, "/api/chat/sessions/batch-delete", chatSessionController::batchDelete);
        http().route(HTTPMethod.PUT, "/api/chat/sessions/:sessionId", chatSessionController::update);
        http().route(HTTPMethod.POST, "/api/chat/sessions/:sessionId/feedback", chatSessionController::submitFeedback);
    }

    private void bindSessionRuntime() {
        bind(LLMCallExecutor.class);
        bind(SubAgentAssembler.class);
        bind(ChatMessageService.class);
        bind(AgentSessionManager.class);
        bind(SessionCreateHelper.class);
    }

    private void registerSseEndpoints() {
        var sseConfig = config(PatchedServerSentEventConfig.class, "core-ai-server-sse");
        sseConfig.intercept(new SseAuthInterceptor(bean(RequestAuthenticator.class)));
        sseConfig.listen(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class));
        sseConfig.listen(HTTPMethod.POST, "/api/litellm/v1/chat/completions", Object.class, bind(LiteLLMProxyChannelListener.class));
    }
}
