package ai.core.server.web.session;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.User;
import ai.core.utils.JsonUtil;
import core.framework.internal.web.session.ReadOnlySession;
import core.framework.internal.web.session.SessionImpl;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionIdentityTest {
    private static final String USER_ID = "user@test.com";

    @Test
    void expiredIdentityWithReadOnlySessionServesRefreshWithoutWrite() {
        var session = new SessionImpl("localhost");
        var stale = identity(ZonedDateTime.now().minusMinutes(10), List.of("stale"));
        session.set(SessionIdentity.USER_IDENTITY, JsonUtil.toJson(stale));
        var service = service(ReadOnlySession.of(session));

        var refreshed = service.getUserIdentityOrNull();

        assertNotNull(refreshed);
        assertEquals(USER_ID, refreshed.userId);
        assertEquals(List.of("chat.use"), refreshed.permissions);
        assertTrue(refreshed.expiredAt.isAfter(ZonedDateTime.now()));
        var persisted = JsonUtil.fromJson(UserIdentity.class, session.get(SessionIdentity.USER_IDENTITY).orElseThrow());
        assertEquals("stale", persisted.permissions.get(0));
    }

    @Test
    void expiredIdentityWithWritableSessionRefreshesAndPersists() {
        var session = new SessionImpl("localhost");
        var stale = identity(ZonedDateTime.now().minusMinutes(10), List.of("stale"));
        session.set(SessionIdentity.USER_IDENTITY, JsonUtil.toJson(stale));
        var service = service(session);

        var refreshed = service.getUserIdentityOrNull();

        assertNotNull(refreshed);
        var persisted = JsonUtil.fromJson(UserIdentity.class, session.get(SessionIdentity.USER_IDENTITY).orElseThrow());
        assertEquals(List.of("chat.use"), persisted.permissions);
        assertTrue(refreshed.expiredAt.isEqual(persisted.expiredAt));
    }

    @Test
    void freshIdentityIsNotRefreshed() {
        var session = new SessionImpl("localhost");
        var fresh = identity(ZonedDateTime.now().plusMinutes(5), List.of("chat.use"));
        session.set(SessionIdentity.USER_IDENTITY, JsonUtil.toJson(fresh));
        var service = service(session);

        var identity = service.getUserIdentityOrNull();

        assertEquals(USER_ID, identity.userId);
        assertTrue(fresh.expiredAt.isEqual(identity.expiredAt));
        assertEquals(fresh.permissions, identity.permissions);
    }

    private SessionIdentity service(core.framework.web.Session session) {
        var service = new SessionIdentity();
        var webContext = mock(WebContext.class);
        var request = mock(Request.class);
        when(request.session()).thenReturn(session);
        when(webContext.request()).thenReturn(request);
        service.webContext = webContext;
        service.permissionService = permissionService();
        service.userCollection = userCollection();
        return service;
    }

    private UserIdentity identity(ZonedDateTime expiredAt, List<String> permissions) {
        var identity = new UserIdentity();
        identity.userId = USER_ID;
        identity.name = "test";
        identity.role = "user";
        identity.permissions = permissions;
        identity.expiredAt = expiredAt;
        return identity;
    }

    @SuppressWarnings("unchecked")
    private PermissionService permissionService() {
        var service = mock(PermissionService.class);
        when(service.permissionsOf(USER_ID)).thenReturn(List.of("chat.use"));
        return service;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> userCollection() {
        var user = new User();
        user.id = USER_ID;
        user.name = "test";
        user.role = "user";
        var collection = mock(MongoCollection.class);
        when(collection.get(USER_ID)).thenReturn(Optional.of(user));
        return collection;
    }
}
