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
import ai.core.server.web.sse.CliProxyChannelListener;
import ai.core.server.web.sse.SseAuthInterceptor;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @author stephen
 */
public class SessionModule extends Module {
    // The Go terminal gateway (core-ai-terminal-gateway/main.go) reads TICKET_SECRET and uses
    // its raw string bytes directly ([]byte(secret)) as the HMAC key -- no hex decoding. Both
    // sides read the SAME env/property value, so Java MUST interpret it identically (raw UTF-8
    // bytes), never hex-decode it, or the two sides derive different keys and every minted
    // ticket fails verification at the gateway. An empty/missing value yields a zero-length
    // secret, which SandboxTerminalService treats as "gate disabled".
    private static byte[] parseTicketSecret(String raw) {
        return raw.isBlank() ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8);
    }

    private SessionRegistry sessionRegistry;
    private AgentSessionManager agentSessionManager;
    private SessionActivityRegistry activityRegistry;

    @Override
    protected void initialize() {
        bindSessionRuntime();
        // Bound here (not SandboxModule) because SandboxService's own module loads
        // before this one in ServerApp, but SessionRegistry/AgentSessionManager are
        // bound in this module -- bean() cannot resolve a not-yet-bound type.
        registerSandboxTerminal();
        registerSseEndpoints();
        schedule().fixedRate("idle-session-cleanup", bind(IdleSessionCleanupJob.class), Duration.ofMinutes(5));
        api().service(ChatSessionWebService.class, bind(ChatSessionWebServiceImpl.class));
    }

    private void bindSessionRuntime() {
        bind(SubAgentAssembler.class);
        bind(SessionAgentHelper.class);
        sessionRegistry = bind(SessionRegistry.class);
        bind(ChatMessageService.class);
        activityRegistry = bind(new SessionActivityRegistry(bean(JedisPool.class)));
        agentSessionManager = bind(AgentSessionManager.class);
        // finished async tool calls are pushed into the live session that issued them
        bean(ai.core.server.asynctask.AsyncToolTaskService.class).setSessionLocator(agentSessionManager::getSession);
        bind(SessionCreateHelper.class);
    }

    private void registerSandboxTerminal() {
        var sandboxService = bean(SandboxService.class);
        var terminalRuntimeResolver = new SandboxTerminalRuntimeResolver(sandboxService);
        var service = new SandboxTerminalService(sessionRegistry::requireAccessible,
                terminalRuntimeResolver::resolveTerminalRuntime, activityRegistry, agentSessionManager::touchActivity);
        service.enabled = "true".equals(property("sys.sandbox.terminal.enabled").orElse("false"));
        service.ticketSecret = parseTicketSecret(property("sys.sandbox.terminal.ticketSecret").orElse(""));
        service.gatewayUrl = property("sys.sandbox.terminal.gatewayUrl").orElse("");
        bind(service);
        api().service(SandboxTerminalWebService.class, bind(SandboxTerminalWebServiceImpl.class));
    }

    private void registerSseEndpoints() {
        var registry = bean(SseEndpointRegistry.class);
        registry.addInterceptor(new SseAuthInterceptor(bean(RequestAuthenticator.class),
                bean(ai.core.server.web.session.SessionIdentity.class),
                bean(ai.core.server.apiuser.PermissionService.class)));
        registry.register(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class), false);
        var cliProxyListener = bind(CliProxyChannelListener.class);
        registry.register(HTTPMethod.POST, CliProxyChannelListener.PATH, Object.class, cliProxyListener, false);
        // deprecated alias for older CLI binaries, removed once the CLI fleet has migrated to /api/cli/v1
        registry.register(HTTPMethod.POST, CliProxyChannelListener.LEGACY_PATH, Object.class, cliProxyListener, false);
    }
}
