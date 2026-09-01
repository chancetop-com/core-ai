package ai.core.server;

import ai.core.api.server.sandbox.SandboxTerminalWebService;
import ai.core.api.server.session.ChatSessionWebService;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.sandbox.terminal.SandboxTerminalService;
import ai.core.server.sandbox.terminal.SandboxTerminalWebServiceImpl;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionActivityRegistry;
import ai.core.server.session.SessionAgentHelper;
import ai.core.server.session.SessionRegistry;
import ai.core.server.web.ChatSessionWebServiceImpl;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.LiteLLMProxyChannelListener;
import ai.core.server.web.sse.SseAuthInterceptor;
import ai.core.server.web.sse.TerminalStreamChannelListener;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

/**
 * @author stephen
 */
public class SessionModule extends Module {
    private SessionRegistry sessionRegistry;
    private AgentSessionManager agentSessionManager;
    private SessionActivityRegistry activityRegistry;

    @Override
    protected void initialize() {
        bindSessionRuntime();
        registerSseEndpoints();
        schedule().fixedRate("idle-session-cleanup", bind(IdleSessionCleanupJob.class), Duration.ofMinutes(5));
        api().service(ChatSessionWebService.class, bind(ChatSessionWebServiceImpl.class));
        // Bound here (not SandboxModule) because SandboxService's own module loads
        // before this one in ServerApp, but SessionRegistry/AgentSessionManager are
        // bound in this module -- bean() cannot resolve a not-yet-bound type.
        registerSandboxTerminal();
    }

    private void bindSessionRuntime() {
        bind(SubAgentAssembler.class);
        bind(SessionAgentHelper.class);
        sessionRegistry = bind(SessionRegistry.class);
        bind(ChatMessageService.class);
        activityRegistry = bind(new SessionActivityRegistry(bean(JedisPool.class)));
        agentSessionManager = bind(AgentSessionManager.class);
        bind(SessionCreateHelper.class);
    }

    private void registerSandboxTerminal() {
        var sandboxService = bean(SandboxService.class);
        var terminalRuntimeResolver = new SandboxTerminalRuntimeResolver(sandboxService);
        var service = new SandboxTerminalService(sessionRegistry::requireAccessible,
                terminalRuntimeResolver::resolveTerminalRuntime, activityRegistry, agentSessionManager::touchActivity);
        service.enabled = "true".equals(property("sys.sandbox.terminal.enabled").orElse("false"));
        bind(service);
        api().service(SandboxTerminalWebService.class, bind(SandboxTerminalWebServiceImpl.class));
    }

    private void registerSseEndpoints() {
        var registry = bean(SseEndpointRegistry.class);
        registry.addInterceptor(new SseAuthInterceptor(bean(RequestAuthenticator.class),
                bean(ai.core.server.web.session.SessionIdentity.class),
                bean(ai.core.server.apiuser.PermissionService.class)));
        registry.register(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class), false);
        registry.register(HTTPMethod.POST, "/api/litellm/v1/chat/completions", Object.class, bind(LiteLLMProxyChannelListener.class), false);
        registry.register(HTTPMethod.GET, "/api/sessions/sandbox-terminal/events", Object.class, bind(TerminalStreamChannelListener.class), false);
    }
}
