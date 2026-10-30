package ai.core.server.trace.web.trace;

import ai.core.server.domain.User;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibility;
import ai.core.server.trace.service.TraceVisibilityResolver;
import ai.core.server.web.auth.AuthContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TraceWebServiceImpl wires trace read access through {@link TraceAccessControl}
 * (branch semantics are covered by TraceAccessControlTest).
 *
 * @author stephen
 */
class TraceWebServiceImplTest {
    @Test
    void traceReadableViaAccessControl() {
        var service = service("viewer-1");
        var trace = trace("trace-1", "runner-1");
        when(service.traceService.getWithPayload("trace-1")).thenReturn(trace);
        when(service.traceAccessControl.canRead(eq(trace), eq("viewer-1"), any(TraceVisibility.class))).thenReturn(Boolean.TRUE);

        assertDoesNotThrow(() -> service.get("trace-1"));
    }

    @Test
    void traceHiddenWhenAccessControlDenies() {
        var service = service("viewer-1");
        var trace = trace("trace-1", "runner-1");
        when(service.traceService.getWithPayload("trace-1")).thenReturn(trace);
        when(service.traceAccessControl.canRead(eq(trace), eq("viewer-1"), any(TraceVisibility.class))).thenReturn(Boolean.FALSE);

        assertThrows(NotFoundException.class, () -> service.get("trace-1"));
    }

    @Test
    void traceViewFlagsServerDerivedSessionIds() {
        var service = service("viewer-1");
        var derived = trace("trace-1", "runner-1");
        derived.sessionId = "fp1-0123456789abcdef0123456789abcdef";
        when(service.traceService.getWithPayload("trace-1")).thenReturn(derived);
        when(service.traceAccessControl.canRead(eq(derived), eq("viewer-1"), any(TraceVisibility.class))).thenReturn(Boolean.TRUE);
        var clientProvided = trace("trace-2", "runner-1");
        clientProvided.sessionId = "claude-session-1";
        when(service.traceService.getWithPayload("trace-2")).thenReturn(clientProvided);
        when(service.traceAccessControl.canRead(eq(clientProvided), eq("viewer-1"), any(TraceVisibility.class))).thenReturn(Boolean.TRUE);

        assertEquals(Boolean.TRUE, service.get("trace-1").sessionIdDerived);
        assertEquals(Boolean.FALSE, service.get("trace-2").sessionIdDerived);
    }

    @Test
    void scopeUsersReturnsVisibleUsers() {
        var service = service("viewer-1");
        var apiUser = new User();
        apiUser.id = "api:acme";
        apiUser.name = "Acme";
        apiUser.userType = "api";
        when(service.traceVisibilityResolver.visibleUsers("viewer-1")).thenReturn(List.of(apiUser));

        var response = service.scopeUsers();

        assertEquals(1, response.users.size());
        assertEquals("api:acme", response.users.getFirst().userId);
        assertEquals("api", response.users.getFirst().userType);
    }

    private TraceWebServiceImpl service(String userId) {
        var service = new TraceWebServiceImpl();
        service.traceService = mock(TraceService.class);
        service.traceAccessControl = mock(TraceAccessControl.class);
        service.traceVisibilityResolver = mock(TraceVisibilityResolver.class);
        service.userCollection = userCollection();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn(userId);
        when(service.userCollection.get(userId)).thenReturn(Optional.empty());
        when(service.traceVisibilityResolver.resolve(userId)).thenReturn(TraceVisibility.of(Set.of(userId)));
        return service;
    }

    private Trace trace(String traceId, String userId) {
        var trace = new Trace();
        trace.id = traceId;
        trace.traceId = traceId;
        trace.userId = userId;
        return trace;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> userCollection() {
        return (MongoCollection<User>) mock(MongoCollection.class);
    }
}
