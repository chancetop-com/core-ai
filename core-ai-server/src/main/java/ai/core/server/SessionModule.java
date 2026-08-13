package ai.core.server;

import ai.core.api.server.session.ChatSessionWebService;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionAgentHelper;
import ai.core.server.session.SessionRegistry;
import ai.core.server.web.ChatSessionWebServiceImpl;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.LiteLLMProxyChannelListener;
import ai.core.server.web.sse.SseAuthInterceptor;
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
        api().service(ChatSessionWebService.class, bind(ChatSessionWebServiceImpl.class));
    }

    private void bindSessionRuntime() {
        bind(SubAgentAssembler.class);
        bind(SessionAgentHelper.class);
        bind(SessionRegistry.class);
        bind(ChatMessageService.class);
        bind(AgentSessionManager.class);
        bind(SessionCreateHelper.class);
    }

    private void registerSseEndpoints() {
        var registry = bean(SseEndpointRegistry.class);
        registry.addInterceptor(new SseAuthInterceptor(bean(RequestAuthenticator.class),
                bean(ai.core.server.web.session.SessionIdentity.class),
                bean(ai.core.server.apiuser.PermissionService.class)));
        registry.register(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class), false);
        registry.register(HTTPMethod.POST, "/api/litellm/v1/chat/completions", Object.class, bind(LiteLLMProxyChannelListener.class), false);
    }
}
