package ai.core.server.sandboxhub;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.sandboxhub.SandboxHubCallResponse;
import ai.core.api.server.sandboxhub.SandboxHubSessionView;
import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.server.messaging.CommandType;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.web.PodLocalExecutor;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.auth.SandboxPrincipal;
import ai.core.utils.JsonUtil;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing and timeouts of the sandbox hub endpoints. The session lives on exactly one replica, so every
 * endpoint has to run on the owning pod: on the owner it must run in-process, everywhere else it must
 * forward over RPC and wait long enough for the owner to answer (call timeout + margin).
 *
 * @author xander
 */
class SandboxHubWebServiceImplTest {
    private static final String SESSION_ID = "s1";
    private static final String TOKEN = "cst.token";

    private SandboxHubWebServiceImpl impl;
    private SandboxHubService hubService;
    private SessionTokenService tokenService;
    private PodLocalExecutor podLocalExecutor;

    @BeforeEach
    void setUp() {
        impl = new SandboxHubWebServiceImpl();
        hubService = mock(SandboxHubService.class);
        tokenService = mock(SessionTokenService.class);
        podLocalExecutor = mock(PodLocalExecutor.class);
        var rpcClient = mock(RpcClient.class);

        var webContext = mock(WebContext.class);
        var request = mock(Request.class);
        when(webContext.get(AuthContext.SANDBOX_PRINCIPAL_KEY)).thenReturn(new SandboxPrincipal(SESSION_ID, "u1", "sb1"));
        when(webContext.request()).thenReturn(request);
        when(request.header("Authorization")).thenReturn(Optional.of("Bearer " + TOKEN));
        when(tokenService.verify(TOKEN)).thenReturn(new SessionToken(SESSION_ID, "u1", "sb1", 1L, 1_900_000_000L, "n"));
        when(rpcClient.newRequestId()).thenReturn("req-1");
        runLocalOperation();

        impl.webContext = webContext;
        impl.sessionTokenService = tokenService;
        impl.hubService = hubService;
        impl.podLocalExecutor = podLocalExecutor;
        impl.rpcClient = rpcClient;
    }

    @Test
    void callRunsInProcessWhenThisPodOwnsTheSession() {
        var expected = new SandboxHubCallResponse();
        when(hubService.call(any(), eq("menu_hub_search"), any())).thenReturn(expected);

        var response = impl.call("menu_hub_search", request("{}", 30));

        assertEquals(expected, response);
        assertEquals(SESSION_ID, captured().sessionId());
    }

    @Test
    void callForwardsToTheOwningPodWithTimeoutPlusMargin() {
        forwardCall(new SandboxHubCallResponse());

        impl.call("menu_hub_search", request("{}", 30));

        var captured = captured();
        assertEquals(SESSION_ID, captured.sessionId());
        assertEquals(CommandType.SANDBOX_TOOL_CALL, captured.command().type());
        assertEquals(SESSION_ID, captured.command().sessionId());
        assertEquals("u1", captured.command().userId());
        assertEquals("req-1", captured.command().requestId());
        assertEquals(Duration.ofSeconds(35), captured.timeout());
    }

    @Test
    void callWithoutTimeoutWaitsForTheKindDefault() {
        forwardCall(new SandboxHubCallResponse());

        impl.call("menu_hub_search", request("{}", null));

        assertEquals(Duration.ofSeconds(SandboxHubCatalog.AGENT_TIMEOUT_SECONDS + 5), captured().timeout());
    }

    @Test
    void callTimeoutIsClampedToTheHubCeiling() {
        forwardCall(new SandboxHubCallResponse());

        impl.call("menu_hub_search", request("{}", 10_000));

        assertEquals(Duration.ofSeconds(605), captured().timeout());
    }

    @Test
    void callPayloadCarriesTheSandboxSoTheOwnerCanAuditIt() {
        forwardCall(new SandboxHubCallResponse());

        impl.call("menu_hub_search", request("{\"q\":\"x\"}", 30));

        var payload = JsonUtil.fromJson(SandboxHubCallCommandPayload.class, captured().command().payload());
        assertEquals("menu_hub_search", payload.name);
        assertEquals("sb1", payload.sandboxId);
        assertEquals("{\"q\":\"x\"}", payload.arguments);
        assertEquals(30, payload.timeoutSeconds);
    }

    @Test
    void taskPollForwardsWithTaskId() {
        forwardCall(new SandboxHubCallResponse());
        impl.task("task-7");

        var captured = captured();
        assertEquals(CommandType.SANDBOX_TOOL_POLL, captured.command().type());
        assertEquals("task-7", JsonUtil.fromJson(Map.class, captured.command().payload()).get("taskId"));
        assertEquals(Duration.ofSeconds(60), captured.timeout());
    }

    @Test
    void readsAreAnsweredFromTheOwningPodsSnapshot() {
        var detail = new SandboxHubToolDetail();
        detail.name = "menu_hub_search";
        detail.kind = "mcp";
        var snapshot = SandboxHubCatalogSnapshot.of("menu-agent", List.of(detail));
        doReturn(snapshot).when(podLocalExecutor).execute(anyString(), any(), any(), any(), any());

        var catalog = impl.catalog();

        var captured = captured();
        assertEquals(CommandType.SANDBOX_CATALOG, captured.command().type());
        assertEquals(Duration.ofSeconds(60), captured.timeout());
        assertEquals(SESSION_ID, catalog.sessionId);
        assertEquals("sb1", catalog.sandboxId);
        assertEquals("menu-agent", catalog.agentName);
        assertEquals(1, catalog.tools.size());
    }

    @Test
    void meReportsTheLiveBindingFromTheToken() {
        when(hubService.snapshot(any())).thenReturn(SandboxHubCatalogSnapshot.of("menu-agent", List.of()));

        SandboxHubSessionView view = impl.me();

        assertEquals(SESSION_ID, view.sessionId);
        assertEquals("sb1", view.sandboxId);
        assertEquals("menu-agent", view.agentName);
        assertTrue(view.expiresAt.startsWith("2030-"));
    }

    @Test
    void requestWithoutASandboxPrincipalIsRejected() {
        when(impl.webContext.get(AuthContext.SANDBOX_PRINCIPAL_KEY)).thenReturn(null);

        assertThrows(UnauthorizedException.class, impl::catalog);
        verify(podLocalExecutor, never()).execute(anyString(), any(), any(), any(), any());
    }

    @Test
    void tokenThatNoLongerVerifiesIsRejectedEvenWithAPrincipal() {
        doThrow(new IllegalArgumentException("expired")).when(tokenService).verify(TOKEN);

        assertThrows(UnauthorizedException.class, impl::catalog);
    }

    // The owning pod answers in-process; the fixture is the executor running the local operation.
    private void runLocalOperation() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(podLocalExecutor).execute(anyString(), any(), any(), any(), any());
    }

    private void forwardCall(SandboxHubCallResponse response) {
        doReturn(response).when(podLocalExecutor).execute(anyString(), any(), any(), any(), any());
    }

    private RpcCapture captured() {
        var sessionId = ArgumentCaptor.forClass(String.class);
        var command = ArgumentCaptor.forClass(SessionCommand.class);
        var timeout = ArgumentCaptor.forClass(Duration.class);
        verify(podLocalExecutor).execute(sessionId.capture(), any(), command.capture(), any(), timeout.capture());
        return new RpcCapture(sessionId.getValue(), command.getValue(), timeout.getValue());
    }

    private HubCallRequest request(String arguments, Integer timeoutSeconds) {
        var request = new HubCallRequest();
        request.arguments = arguments;
        request.timeoutSeconds = timeoutSeconds;
        return request;
    }

    private record RpcCapture(String sessionId, SessionCommand command, Duration timeout) {
    }
}