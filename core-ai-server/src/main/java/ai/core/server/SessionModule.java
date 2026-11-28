package ai.core.server;

import ai.core.api.server.sandbox.SandboxTerminalWebService;
import ai.core.api.server.session.ChatSessionWebService;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.user.UserNotificationWebService;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.sandbox.terminal.SandboxTerminalService;
import ai.core.server.sandbox.terminal.SandboxTerminalWebServiceImpl;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.server.schedule.IdleSessionCleanupJob;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.AwtImageShrinker;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.NotificationSettingsService;
import ai.core.server.session.PgSessionActivityStore;
import ai.core.server.session.SessionActivityRegistry;
import ai.core.server.session.SessionActivityStore;
import ai.core.server.session.SessionAgentHelper;
import ai.core.server.session.SessionCompletionNotifier;
import ai.core.server.session.SessionNoticePrompt;
import ai.core.server.session.SessionReadService;
import ai.core.server.session.SessionRegistry;
import ai.core.server.session.SessionSearchService;
import ai.core.server.web.ChatSessionWebServiceImpl;
import ai.core.server.web.SessionCreateHelper;
import ai.core.server.web.UserNotificationWebServiceImpl;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.auth.SubUserDelegationResolver;
import ai.core.server.web.sse.AgentSessionChannelListener;
import ai.core.server.web.sse.CliProxyChannelListener;
import ai.core.server.web.sse.InternalSessionEventChannelListener;
import ai.core.server.web.sse.InternalSsePreflight;
import ai.core.server.web.sse.SseAuthInterceptor;
import ai.core.server.workspace.WorkspaceSessionBinder;
import ai.core.server.workspace.WorkspaceTerminalActivityListener;
import ai.core.utils.ImageDownscaler;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

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
        // self-service notification target: any signed-in user configures their own channel
        api().service(UserNotificationWebService.class, bind(UserNotificationWebServiceImpl.class));
    }

    private void bindSessionRuntime() {
        bind(SubAgentAssembler.class);
        bind(SessionAgentHelper.class);        // history images are resent on every later turn, so oversized ones are re-encoded as they enter
        // a session; the AWT encoder is JVM-only, which is why it is registered here and not in core-ai
        ImageDownscaler.register(new AwtImageShrinker());
        sessionRegistry = bind(SessionRegistry.class);
        sessionRegistry.workspaceSessionBinder(bean(WorkspaceSessionBinder.class));
        bind(ChatMessageService.class);
        bind(SessionSearchService.class);
        bind(SessionReadService.class);
        activityRegistry = bind(SessionActivityRegistry.of(activityStore()));        // the notice log is bound in ChannelInfrastructureModule; the block it feeds belongs to this runtime
        bind(SessionNoticePrompt.class);
        bind(SessionCompletionNotifier.class);
        bind(NotificationSettingsService.class);
        bindSessionMedia();
        agentSessionManager = bind(AgentSessionManager.class);
        // finished async tool calls are delivered over the command bus; MessagingRuntimeModule wires
        // the dispatcher once CommandPublisher exists (it loads after this module)
        bind(SessionCreateHelper.class);
    }

    private SessionActivityStore activityStore() {
        return bind(new PgSessionActivityStore());
    }

    /**
     * Media bytes are owned by the store, not by messages. Bound before AgentSessionManager because the
     * session execution context carries the store to every agent, tool and subagent.
     */
    private void bindSessionMedia() {
        bind(new ai.core.server.media.BlobSessionMediaStore(
                bean(ai.core.server.blob.ObjectStorageServiceResolver.class),
                bean(ai.core.server.domain.SessionAttachmentRefRepository.class)));
        bind(ai.core.server.media.SessionMediaMaintenanceJob.class);
        schedule().fixedRate("session-media-maintenance", bean(ai.core.server.media.SessionMediaMaintenanceJob.class),
                Duration.ofHours(1));
        http().route(HTTPMethod.GET, "/api/sessions/:sessionId/media/:mediaId",
                bind(ai.core.server.media.SessionMediaController.class));
    }

    private void registerSandboxTerminal() {
        var sandboxService = bean(SandboxService.class);
        var terminalRuntimeResolver = new SandboxTerminalRuntimeResolver(sandboxService);
        var service = new SandboxTerminalService(sessionRegistry::requireAccessible,
                terminalRuntimeResolver::resolveTerminalRuntime, activityRegistry, agentSessionManager::touchActivity);
        service.activityListener(bean(WorkspaceTerminalActivityListener.class));
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
                bean(ai.core.server.apiuser.PermissionService.class),
                bean(SubUserDelegationResolver.class)));
        registry.preflight(HTTPMethod.GET, SseAuthInterceptor.INTERNAL_EVENTS_PATH,
                new InternalSsePreflight(bean(ai.core.server.messaging.PodRegistry.class),
                        bean(ai.core.server.messaging.SessionOwnershipRegistry.class),
                        property("sys.internal.token").orElse(""),
                        property("sys.internal.token.previous").orElse("")));
        registry.register(HTTPMethod.GET, SseAuthInterceptor.INTERNAL_EVENTS_PATH, SseBaseEvent.class,
                bind(InternalSessionEventChannelListener.class), false);
        registry.register(HTTPMethod.PUT, "/api/sessions/events", SseBaseEvent.class, bind(AgentSessionChannelListener.class), false);
        var cliProxyListener = bind(CliProxyChannelListener.class);
        registry.register(HTTPMethod.POST, CliProxyChannelListener.PATH, Object.class, cliProxyListener, false);
        // deprecated alias for older CLI binaries, removed once the CLI fleet has migrated to /api/cli/v1
        registry.register(HTTPMethod.POST, CliProxyChannelListener.LEGACY_PATH, Object.class, cliProxyListener, false);
    }
}
