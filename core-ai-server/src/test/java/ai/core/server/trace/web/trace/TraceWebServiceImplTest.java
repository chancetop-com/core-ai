package ai.core.server.trace.web.trace;

import ai.core.server.domain.User;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceService;
import ai.core.server.web.auth.AuthContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        when(service.traceService.get("trace-1")).thenReturn(trace);
        when(service.traceAccessControl.canRead(trace, "viewer-1", false)).thenReturn(Boolean.TRUE);

        assertDoesNotThrow(() -> service.get("trace-1"));
    }

    @Test
    void traceHiddenWhenAccessControlDenies() {
        var service = service("viewer-1");
        var trace = trace("trace-1", "runner-1");
        when(service.traceService.get("trace-1")).thenReturn(trace);
        when(service.traceAccessControl.canRead(trace, "viewer-1", false)).thenReturn(Boolean.FALSE);

        assertThrows(NotFoundException.class, () -> service.get("trace-1"));
    }

    private TraceWebServiceImpl service(String userId) {
        var service = new TraceWebServiceImpl();
        service.traceService = mock(TraceService.class);
        service.traceAccessControl = mock(TraceAccessControl.class);
        service.userCollection = userCollection();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn(userId);
        when(service.userCollection.get(userId)).thenReturn(Optional.empty());
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
