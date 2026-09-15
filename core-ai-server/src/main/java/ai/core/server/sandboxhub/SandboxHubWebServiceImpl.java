package ai.core.server.sandboxhub;

import ai.core.api.server.SandboxHubWebService;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.sandboxhub.SandboxHubCallResponse;
import ai.core.api.server.sandboxhub.SandboxHubCatalogResponse;
import ai.core.api.server.sandboxhub.SandboxHubSessionView;
import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.api.server.sandboxhub.SandboxHubToolSearchRequest;
import ai.core.api.server.sandboxhub.SandboxHubToolsResponse;
import ai.core.sandbox.SandboxConstants;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.sandboxhub.SandboxHubService.SandboxHubSession;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;

import java.time.Duration;
import java.time.Instant;

/**
 * Sandbox hub endpoints. Auth is the {@code cst_} session token: the interceptor already verified it
 * against the session's live sandbox binding, so authorization here is only "which session does this
 * token name" — the session id always comes from the token, never from the request.
 * <p>
 * The session itself lives on one replica, so anything that reads or runs the owning agent's tools is
 * executed there: the owning pod builds the catalog and runs the call, this pod renders the response.
 * Reads therefore work from any replica without a session cache here.
 * <p>
 * Permission checks are bypassed on purpose: the permission model answers "may this user use this
 * capability", which the owning session already answered when it was configured, and the sandbox
 * acts strictly as that session.
 *
 * @author xander
 */
public class SandboxHubWebServiceImpl implements SandboxHubWebService {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int RPC_TIMEOUT_MARGIN_SECONDS = 5;
    private static final int MAX_CALL_TIMEOUT_SECONDS = (int) (SandboxConstants.MAX_TOOL_TIMEOUT_MS / 1000) + RPC_TIMEOUT_MARGIN_SECONDS;
    private static final Duration CATALOG_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    // Without an explicit timeout the owner applies the kind's default (300s at most) and answers within it,
    // so waiting that long plus the margin never turns a slow-but-healthy call into a replica error.
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(SandboxHubCatalog.AGENT_TIMEOUT_SECONDS + RPC_TIMEOUT_MARGIN_SECONDS);

    @Inject
    WebContext webContext;
    @Inject
    SessionTokenService sessionTokenService;
    @Inject
    SandboxHubService hubService;
    @Inject
    PodLocalExecutor podLocalExecutor;
    @Inject
    RpcClient rpcClient;

    @Override
    @PermissionsBypass
    public SandboxHubSessionView me() {
        var session = session();
        return SandboxHubViews.me(snapshot(session), session.sessionId(), session.sandboxId(), session.expiresAt());
    }

    @Override
    @PermissionsBypass
    public SandboxHubCatalogResponse catalog() {
        var session = session();
        return SandboxHubViews.catalog(snapshot(session), session.sessionId(), session.sandboxId(), session.expiresAt());
    }

    @Override
    @PermissionsBypass
    public SandboxHubToolsResponse tools(SandboxHubToolSearchRequest request) {
        return SandboxHubViews.tools(snapshot(session()), request);
    }

    @Override
    @PermissionsBypass
    public SandboxHubToolDetail describe(String name) {
        return SandboxHubViews.describe(snapshot(session()), name);
    }

    @Override
    @PermissionsBypass
    public SandboxHubCallResponse call(String name, HubCallRequest request) {
        var session = session();
        return podLocalExecutor.execute(session.sessionId(),
                () -> hubService.call(session, name, request),
                SessionCommand.sandboxToolCall(session.sessionId(), session.userId(), callPayload(session, name, request), rpcClient.newRequestId()),
                SandboxHubCallResponse.class,
                callTimeout(request));
    }

    @Override
    @PermissionsBypass
    public SandboxHubCallResponse task(String taskId) {
        var session = session();
        return podLocalExecutor.execute(session.sessionId(),
                () -> hubService.task(session, taskId),
                SessionCommand.sandboxToolPoll(session.sessionId(), session.userId(), taskId, rpcClient.newRequestId()),
                SandboxHubCallResponse.class,
                POLL_TIMEOUT);
    }

    private SandboxHubCatalogSnapshot snapshot(SandboxHubSession session) {
        return podLocalExecutor.execute(session.sessionId(),
                () -> hubService.snapshot(session),
                SessionCommand.sandboxCatalog(session.sessionId(), session.userId(), rpcClient.newRequestId()),
                SandboxHubCatalogSnapshot.class,
                CATALOG_TIMEOUT);
    }

    // The sandbox id is not part of the session on the owning pod, so it travels with the call and lands
    // in the audit row (hub_calls) of the executions this script triggers.
    private String callPayload(SandboxHubSession session, String name, HubCallRequest request) {
        var payload = new SandboxHubCallCommandPayload();
        payload.name = name;
        payload.sandboxId = session.sandboxId();
        payload.arguments = request != null ? request.arguments : null;
        payload.timeoutSeconds = request != null ? request.timeoutSeconds : null;
        return JsonUtil.toJson(payload);
    }

    private Duration callTimeout(HubCallRequest request) {
        if (request == null || request.timeoutSeconds == null) return DEFAULT_CALL_TIMEOUT;
        return Duration.ofSeconds(Math.min(request.timeoutSeconds + RPC_TIMEOUT_MARGIN_SECONDS, MAX_CALL_TIMEOUT_SECONDS));
    }

    // Re-reads the token for its expiry: the principal carries only the identity it scopes, and the
    // token is the one place that knows when the sandbox access ends.
    private SandboxHubSession session() {
        var principal = AuthContext.sandboxPrincipal(webContext);
        if (principal == null) throw new UnauthorizedException("sandbox session token required");
        return new SandboxHubSession(principal.sessionId(), principal.userId(), principal.sandboxId(), expiresAt());
    }

    private String expiresAt() {
        var authorization = webContext.request().header("Authorization").orElse("");
        var token = authorization.startsWith(BEARER_PREFIX) ? authorization.substring(BEARER_PREFIX.length()) : authorization;
        try {
            return Instant.ofEpochSecond(sessionTokenService.verify(token).exp()).toString();
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("invalid sandbox session token: " + e.getMessage(), e);
        }
    }
}
