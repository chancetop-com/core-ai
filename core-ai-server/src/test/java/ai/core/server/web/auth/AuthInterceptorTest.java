package ai.core.server.web.auth;

import ai.core.server.apiuser.ApiUserKeyService;
import ai.core.server.apiuser.ApiUserService;
import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.domain.User;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.web.session.SessionIdentity;
import ai.core.server.web.session.UserIdentity;
import core.framework.mongo.MongoCollection;
import core.framework.web.Invocation;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {
    private static final String MANAGER_ID = "api:manager";
    private static final String SUB_USER_ID = "api:sub-user";
    private static final String KEY_ID = "k_cmk-test";

    private RequestAuthenticator requestAuthenticator;
    private SessionIdentity sessionIdentity;
    private MongoCollection<User> userCollection;
    private AuthInterceptor interceptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        requestAuthenticator = mock(RequestAuthenticator.class);
        sessionIdentity = mock(SessionIdentity.class);
        userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        var delegationResolver = new SubUserDelegationResolver();
        delegationResolver.userCollection = userCollection;

        interceptor = new AuthInterceptor();
        interceptor.requestAuthenticator = requestAuthenticator;
        interceptor.channelConfigStore = mock(ChannelConfigStore.class);
        interceptor.userCollection = userCollection;
        interceptor.sessionIdentity = sessionIdentity;
        interceptor.sandboxService = mock(SandboxService.class);
        interceptor.delegationResolver = delegationResolver;
    }

    @Test
    void delegatesManagementKeyCallToOwnedSubUser() throws Exception {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        when(userCollection.get(SUB_USER_ID)).thenReturn(Optional.of(activeSubUser()));
        var invocation = invocation("/api/agents/order-assistant/call", Map.of(
                "Authorization", "Bearer cmk_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));
        var context = invocation.context();

        interceptor.intercept(invocation);

        verify(context).put(AuthContext.USER_ID_KEY, SUB_USER_ID);
        verify(context).put(AuthContext.KEY_ID_KEY, KEY_ID);
        verify(context).put(AuthContext.DELEGATOR_ID_KEY, MANAGER_ID);
        verify(invocation).proceed();
    }

    @Test
    void managementKeyWithoutHeaderKeepsManagerIdentity() throws Exception {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        var invocation = invocation("/api/agents/order-assistant/call", Map.of("Authorization", "Bearer cmk_test"));
        var context = invocation.context();

        interceptor.intercept(invocation);

        verify(context).put(AuthContext.USER_ID_KEY, MANAGER_ID);
        verify(context).put(AuthContext.KEY_ID_KEY, KEY_ID);
        verify(context, never()).put(eq(AuthContext.DELEGATOR_ID_KEY), any());
        verify(invocation).proceed();
    }

    @Test
    void managementSurfaceRejectsTheHeader() throws Exception {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        var invocation = invocation("/api/api-users", Map.of(
                "Authorization", "Bearer cmk_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));

        assertThrows(BadRequestException.class, () -> interceptor.intercept(invocation));
        verify(invocation, never()).proceed();
    }

    @Test
    void otherSurfacesRejectTheHeader() throws Exception {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(manageKey());
        var invocation = invocation("/api/traces", Map.of(
                "Authorization", "Bearer cmk_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));

        assertThrows(BadRequestException.class, () -> interceptor.intercept(invocation));
        verify(invocation, never()).proceed();
    }

    @Test
    void callScopeKeyCannotDelegate() throws Exception {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any()))
                .thenReturn(new RequestAuthenticator.AuthResult(MANAGER_ID, KEY_ID, ApiUserKeyService.SCOPE_CALL));
        var invocation = invocation("/api/agents/order-assistant/call", Map.of(
                "Authorization", "Bearer ctk_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));

        assertThrows(ForbiddenException.class, () -> interceptor.intercept(invocation));
        verify(invocation, never()).proceed();
    }

    @Test
    void browserSessionRejectsTheHeader() throws Exception {
        var identity = new UserIdentity();
        identity.userId = "internal@example.com";
        when(sessionIdentity.getUserIdentityOrNull()).thenReturn(identity);
        var invocation = invocation("/api/agents/order-assistant/call", Map.of(SubUserDelegationResolver.HEADER, SUB_USER_ID));

        assertThrows(BadRequestException.class, () -> interceptor.intercept(invocation));
        verify(invocation, never()).proceed();
    }

    @Test
    void nonApiKeyRejectsTheHeader() throws Exception {
        when(requestAuthenticator.authenticateFromApiKeyRecord(any())).thenReturn(null);
        var invocation = invocation("/api/agents/order-assistant/call", Map.of(
                "Authorization", "Bearer coreai_test",
                SubUserDelegationResolver.HEADER, SUB_USER_ID));

        assertThrows(BadRequestException.class, () -> interceptor.intercept(invocation));
        verify(invocation, never()).proceed();
    }

    private RequestAuthenticator.AuthResult manageKey() {
        return new RequestAuthenticator.AuthResult(MANAGER_ID, KEY_ID, ApiUserKeyService.SCOPE_MANAGE);
    }

    private User activeSubUser() {
        var user = new User();
        user.id = SUB_USER_ID;
        user.userType = ApiUserService.USER_TYPE_API;
        user.ownerId = MANAGER_ID;
        user.status = "active";
        return user;
    }

    private Invocation invocation(String path, Map<String, String> headers) throws Exception {
        var request = mock(Request.class);
        when(request.path()).thenReturn(path);
        when(request.header(anyString())).thenAnswer(args -> Optional.ofNullable(headers.get(args.getArgument(0))));
        var context = mock(WebContext.class);
        when(context.request()).thenReturn(request);
        var invocation = mock(Invocation.class);
        when(invocation.context()).thenReturn(context);
        when(invocation.proceed()).thenReturn(mock(Response.class));
        return invocation;
    }
}
