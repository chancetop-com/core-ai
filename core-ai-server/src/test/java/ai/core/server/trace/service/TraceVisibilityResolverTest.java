package ai.core.server.trace.service;

import ai.core.server.domain.User;
import ai.core.server.rbac.RoleRegistry;
import ai.core.server.rbac.TraceScopeMode;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceVisibilityResolverTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<User> userCollection = mock(MongoCollection.class);
    private final RoleRegistry roleRegistry = mock(RoleRegistry.class);
    private final TraceVisibilityResolver resolver = resolver();

    @Test
    void adminIsUnrestricted() {
        when(userCollection.get("admin")).thenReturn(Optional.of(user("admin", "admin", "internal")));

        assertTrue(resolver.resolve("admin").unrestricted());
    }

    @Test
    void unconfiguredRoleSeesItselfOnly() {
        when(userCollection.get("u1")).thenReturn(Optional.of(user("u1", "user", "internal")));
        when(roleRegistry.traceScope("user")).thenReturn(TraceScopeMode.SELF);

        var visibility = resolver.resolve("u1");

        assertFalse(visibility.unrestricted());
        assertTrue(visibility.canSee("u1"));
        assertFalse(visibility.canSee("u2"));
        assertEquals(List.of("u1"), visibility.scopedUserIdsOrNull());
    }

    @Test
    void apiScopeAddsEveryApiUserAndCachesTheSet() {
        when(userCollection.get("developer")).thenReturn(Optional.of(user("developer", "developer", "internal")));
        when(roleRegistry.traceScope("developer")).thenReturn(TraceScopeMode.API);
        when(userCollection.find(any(Query.class))).thenReturn(List.of(
                user("api:a", "api", "api"),
                user("api:b", "api", "api")));

        var visibility = resolver.resolve("developer");
        resolver.resolve("developer");

        assertEquals(Set.of("developer", "api:a", "api:b"), Set.copyOf(visibility.scopedUserIdsOrNull()));
        verify(userCollection, times(1)).find(any(Query.class));
    }

    @Test
    void allScopeIsUnrestricted() {
        when(userCollection.get("ops")).thenReturn(Optional.of(user("ops", "ops-lead", "internal")));
        when(roleRegistry.traceScope("ops-lead")).thenReturn(TraceScopeMode.ALL);

        assertTrue(resolver.resolve("ops").unrestricted());
    }

    @Test
    void unknownUserFallsBackToSelf() {
        when(userCollection.get("ghost")).thenReturn(Optional.empty());

        assertEquals(List.of("ghost"), resolver.resolve("ghost").scopedUserIdsOrNull());
    }

    @Test
    void visibleUsersReturnsTheScopedSet() {
        when(userCollection.get("developer")).thenReturn(Optional.of(user("developer", "developer", "internal")));
        when(roleRegistry.traceScope("developer")).thenReturn(TraceScopeMode.API);
        when(userCollection.find(any(Query.class))).thenReturn(List.of(
                user("api:a", "api", "api"),
                user("developer", "developer", "internal")));

        var users = resolver.visibleUsers("developer");

        assertEquals(2, users.size());
        verify(userCollection, times(2)).find(any(Query.class));
    }

    private TraceVisibilityResolver resolver() {
        var resolver = new TraceVisibilityResolver();
        resolver.userCollection = userCollection;
        resolver.roleRegistry = roleRegistry;
        return resolver;
    }

    private User user(String id, String role, String userType) {
        var user = new User();
        user.id = id;
        user.name = id;
        user.role = role;
        user.userType = userType;
        return user;
    }
}
