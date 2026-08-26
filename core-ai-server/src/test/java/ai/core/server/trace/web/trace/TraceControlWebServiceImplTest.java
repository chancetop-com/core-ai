package ai.core.server.trace.web.trace;

import ai.core.api.server.trace.TraceStatusView;
import ai.core.server.domain.User;
import ai.core.server.trace.service.TraceStopService;
import ai.core.server.web.auth.AuthContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceControlWebServiceImplTest {
    private TraceControlWebServiceImpl webService;
    private TraceStopService traceStopService;
    private MongoCollection<User> userCollection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        traceStopService = mock(TraceStopService.class);
        userCollection = mock(MongoCollection.class);

        webService = new TraceControlWebServiceImpl();
        webService.webContext = webContext;
        webService.traceStopService = traceStopService;
        webService.userCollection = userCollection;
    }

    @Test
    void nonAdminIsForbidden() {
        givenRole("user");

        assertThrows(ForbiddenException.class, () -> webService.stop("t1"));

        verify(traceStopService, never()).stop(anyString(), anyString());
    }

    @Test
    void unknownUserIsForbidden() {
        when(userCollection.get("user-1")).thenReturn(Optional.empty());

        assertThrows(ForbiddenException.class, () -> webService.stop("t1"));
    }

    @Test
    void adminStopsTraceAndReturnsOutcome() {
        givenRole("admin");
        when(traceStopService.stop("t1", "user-1")).thenReturn(new TraceStopService.StopOutcome("session", true));

        var response = webService.stop("t1");

        assertEquals("t1", response.traceId);
        assertEquals(TraceStatusView.CANCELLED, response.status);
        assertEquals("session", response.target);
        assertTrue(response.signalled);
    }

    private void givenRole(String role) {
        var user = new User();
        user.id = "user-1";
        user.role = role;
        when(userCollection.get("user-1")).thenReturn(Optional.of(user));
    }
}
