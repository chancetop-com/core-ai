package ai.core.server.web.sse;

import ai.core.server.apiuser.ApiUserKeyService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.auth.SubUserDelegationResolver;
import ai.core.server.web.session.SessionIdentity;
import ai.core.server.web.session.UserIdentity;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SseAuthInterceptorTest {
    private static final String MANAGER_ID = "api:manager";
    private static final String SUB_USER_ID = "api:sub-user";
    private static final String KEY_ID = "k_cmk-test";

    private RequestAuthenticator requestAuthenticator;
    private SessionIdentity sessionIdentity;
    private PermissionService permissionService;
    private SubUserDelegationResolver delegationResolver;
    private SseAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        requestAuthenticator = mock(RequestAuthenticator.class);
        sessionIdentity = mock(SessionIdentity.class);
        permissionService = mock(PermissionService.class);
        delegationResolver = mock(SubUserDelegationResolver.class);
        when(permissionService.has(anyString(), anyString())).thenReturn(Boolean.TRUE);

        interceptor = new SseAuthInterceptor(requestAuthenticator, sessionIdentity, permissionService, delegationResolver);
    }

    @Test
    void delegatesSessionStreamToOwnedSubUser() {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        when(delegationResolver.resolve(any(), any()))
                .thenReturn(new SubUserDelegationResolver.Delegation(SUB_USER_ID, KEY_ID, MANAGER_ID));
        var request = request("/api/sessions/messages/stream", Map.of(
                "Authorization", "Bearer cmk_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));
        var context = webContext();

        interceptor.onConnect(request, context);

        verify(context).put(AuthContext.USER_ID_KEY, SUB_USER_ID);
        verify(context).put(AuthContext.KEY_ID_KEY, KEY_ID);
        verify(context).put(AuthContext.DELEGATOR_ID_KEY, MANAGER_ID);
        verify(permissionService).has(SUB_USER_ID, PermissionCodes.CHAT_USE);
    }

    @Test
    void managementKeyWithoutHeaderKeepsManagerIdentity() {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        when(delegationResolver.resolve(any(), any()))
                .thenReturn(new SubUserDelegationResolver.Delegation(MANAGER_ID, KEY_ID, null));
        var request = request("/api/sessions/messages/stream", Map.of("Authorization", "Bearer cmk_test"));
        var context = webContext();

        interceptor.onConnect(request, context);

        verify(context).put(AuthContext.USER_ID_KEY, MANAGER_ID);
        verify(context).put(AuthContext.KEY_ID_KEY, KEY_ID);
        verify(context, never()).put(eq(AuthContext.DELEGATOR_ID_KEY), any());
    }

    @Test
    void nonDelegableSseRouteRejectsTheHeader() {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        var request = request("/api/notifications/events", Map.of(
                "Authorization", "Bearer cmk_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));
        var context = webContext();

        assertThrows(BadRequestException.class, () -> interceptor.onConnect(request, context));
    }

    @Test
    void browserSessionRejectsTheHeader() {
        var identity = new UserIdentity();
        identity.userId = "internal@example.com";
        when(sessionIdentity.getUserIdentityOrNull()).thenReturn(identity);
        var request = request("/api/sessions/messages/stream", Map.of(SubUserDelegationResolver.HEADER, SUB_USER_ID));
        var context = webContext();

        assertThrows(BadRequestException.class, () -> interceptor.onConnect(request, context));
    }

    private RequestAuthenticator.AuthResult manageKey() {
        return new RequestAuthenticator.AuthResult(MANAGER_ID, KEY_ID, ApiUserKeyService.SCOPE_MANAGE);
    }

    private WebContext webContext() {
        var values = new HashMap<String, Object>();
        var context = mock(WebContext.class);
        doAnswer(args -> {
            values.put(args.getArgument(0), args.getArgument(1));
            return null;
        }).when(context).put(anyString(), any());
        when(context.get(anyString())).thenAnswer(args -> values.get(args.getArgument(0)));
        return context;
    }

    private Request request(String path, Map<String, String> headers) {
        var request = mock(Request.class);
        when(request.path()).thenReturn(path);
        when(request.header(anyString())).thenAnswer(args -> Optional.ofNullable(headers.get(args.getArgument(0))));
        return request;
    }
}
