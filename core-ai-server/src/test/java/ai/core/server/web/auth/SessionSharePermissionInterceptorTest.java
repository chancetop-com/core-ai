package ai.core.server.web.auth;

import ai.core.api.server.share.CreateSessionShareRequest;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.share.SessionShareWebServiceImpl;
import ai.core.server.web.session.SessionIdentity;
import core.framework.web.Invocation;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionSharePermissionInterceptorTest {
    private final PermissionInterceptor interceptor = new PermissionInterceptor();
    private final PermissionService permissions = mock(PermissionService.class);
    private final Response response = Response.text("allowed");

    @BeforeEach
    void setUp() {
        interceptor.permissionService = permissions;
        interceptor.sessionIdentity = mock(SessionIdentity.class);
    }

    @Test
    void creationDeclaresAndEnforcesTracePermissionBeforeInvokingService() throws Exception {
        var method = SessionShareWebServiceImpl.class.getMethod("create", String.class, CreateSessionShareRequest.class);
        assertArrayEquals(new String[]{PermissionCodes.TRACE_VIEW}, method.getAnnotation(PermissionsRequired.class).value());
        var invocation = invocation(method);

        assertThrows(ForbiddenException.class, () -> interceptor.intercept(invocation));
        verify(invocation, never()).proceed();
        when(permissions.has("owner-1", PermissionCodes.TRACE_VIEW)).thenReturn(Boolean.TRUE);

        assertSame(response, interceptor.intercept(invocation));
    }

    @Test
    void ownerCanReachStatusAndRevokeAfterLosingTracePermission() throws Exception {
        var current = SessionShareWebServiceImpl.class.getMethod("current", String.class);
        var revoke = SessionShareWebServiceImpl.class.getMethod("revoke", String.class, String.class);
        assertNotNull(current.getAnnotation(PermissionsBypass.class));
        assertNotNull(revoke.getAnnotation(PermissionsBypass.class));

        assertSame(response, interceptor.intercept(invocation(current)));
        assertSame(response, interceptor.intercept(invocation(revoke)));
    }

    private Invocation invocation(Method method) throws Exception {
        var request = mock(Request.class);
        when(request.path()).thenReturn("/api/sessions/session-1/shares");
        var context = mock(WebContext.class);
        when(context.request()).thenReturn(request);
        when(context.get(AuthContext.USER_ID_KEY)).thenReturn("owner-1");
        var invocation = mock(Invocation.class);
        when(invocation.context()).thenReturn(context);
        when(invocation.annotation(PermissionsRequired.class)).thenReturn(method.getAnnotation(PermissionsRequired.class));
        when(invocation.annotation(PermissionsBypass.class)).thenReturn(method.getAnnotation(PermissionsBypass.class));
        when(invocation.proceed()).thenReturn(response);
        return invocation;
    }
}
