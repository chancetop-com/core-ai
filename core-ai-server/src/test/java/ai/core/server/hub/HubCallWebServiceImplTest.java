package ai.core.server.hub;

import ai.core.api.server.hub.ListHubCallsRequest;
import ai.core.api.server.hub.ListHubCallsResponse;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class HubCallWebServiceImplTest {
    private HubCallWebServiceImpl service;
    private HubCallQueryService queryService;
    private WebContext webContext;
    private MongoCollection<User> userCollection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new HubCallWebServiceImpl();
        queryService = mock(HubCallQueryService.class);
        webContext = mock(WebContext.class);
        userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        service.queryService = queryService;
        service.webContext = webContext;
        service.userCollection = userCollection;
        when(webContext.get("auth.userId")).thenReturn("u1");
        when(userCollection.get(anyString())).thenReturn(Optional.empty());
        when(queryService.list(any(HubCallListFilter.class))).thenReturn(new ListHubCallsResponse());
    }

    @Test
    void listDefaultsToSevenDayWindowAndFirstPage() {
        service.list(new ListHubCallsRequest());

        var filter = capturedFilter();
        assertWithinOneMinute(ZonedDateTime.now().minusDays(7), filter.startFrom);
        assertNull(filter.startTo);
        assertEquals(20, filter.limit);
        assertEquals(0, filter.offset);
        assertNull(filter.kind);
        assertNull(filter.state);
        assertEquals("u1", filter.userId);
    }

    @Test
    void listResolvesRelativeRange() {
        var request = new ListHubCallsRequest();
        request.range = "24h";

        service.list(request);

        assertWithinOneMinute(ZonedDateTime.now().minusHours(24), capturedFilter().startFrom);
    }

    @Test
    void listAcceptsExplicitWindow() {
        var request = new ListHubCallsRequest();
        request.startFrom = "2026-09-01T00:00:00Z";
        request.startTo = "2026-09-02T00:00:00Z";

        service.list(request);

        var filter = capturedFilter();
        assertEquals(ZonedDateTime.parse("2026-09-01T00:00:00Z"), filter.startFrom);
        assertEquals(ZonedDateTime.parse("2026-09-02T00:00:00Z"), filter.startTo);
    }

    @Test
    void listRejectsUnknownRange() {
        var request = new ListHubCallsRequest();
        request.range = "15m";

        assertThrows(BadRequestException.class, () -> service.list(request));
    }

    @Test
    void listClampsPaging() {
        var request = new ListHubCallsRequest();
        request.limit = 5000;
        request.offset = -5;

        service.list(request);

        var filter = capturedFilter();
        assertEquals(100, filter.limit);
        assertEquals(0, filter.offset);
    }

    @Test
    void listRestrictsNonAdminToOwnCalls() {
        var request = new ListHubCallsRequest();
        request.userId = "someone-else";
        request.kind = "agent";
        request.state = "completed";

        service.list(request);

        var filter = capturedFilter();
        assertEquals("u1", filter.userId);
        assertEquals("agent", filter.kind);
        assertEquals("completed", filter.state);
    }

    @Test
    void listLetsAdminFilterByUser() {
        var admin = new User();
        admin.id = "u1";
        admin.role = "admin";
        when(userCollection.get("u1")).thenReturn(Optional.of(admin));
        var request = new ListHubCallsRequest();
        request.userId = "someone-else";

        service.list(request);

        assertEquals("someone-else", capturedFilter().userId);
    }

    @Test
    void listRequiresAuthentication() {
        when(webContext.get("auth.userId")).thenReturn(null);

        assertThrows(UnauthorizedException.class, () -> service.list(new ListHubCallsRequest()));
    }

    private void assertWithinOneMinute(ZonedDateTime expected, ZonedDateTime actual) {
        assertTrue(Math.abs(Duration.between(expected, actual).getSeconds()) <= 60, "expected " + expected + " but was " + actual);
    }

    private HubCallListFilter capturedFilter() {
        var captor = ArgumentCaptor.forClass(HubCallListFilter.class);
        verify(queryService).list(captor.capture());
        return captor.getValue();
    }
}
