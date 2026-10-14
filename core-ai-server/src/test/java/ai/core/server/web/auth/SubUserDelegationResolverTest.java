package ai.core.server.web.auth;

import ai.core.server.apiuser.ApiUserKeyService;
import ai.core.server.apiuser.ApiUserService;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubUserDelegationResolverTest {
    private static final String MANAGER_ID = "api:manager";
    private static final String SUB_USER_ID = "api:sub-user";
    private static final String KEY_ID = "k_cmk-test";
    private static final RequestAuthenticator.AuthResult MANAGE_KEY =
            new RequestAuthenticator.AuthResult(MANAGER_ID, KEY_ID, ApiUserKeyService.SCOPE_MANAGE);

    @Test
    void noHeaderKeepsKeyOwnerIdentity() {
        var delegation = new SubUserDelegationResolver().resolve(request(null), MANAGE_KEY);

        assertFalse(delegation.delegated());
        assertEquals(MANAGER_ID, delegation.effectiveUserId());
        assertEquals(KEY_ID, delegation.keyId());
        assertNull(delegation.delegatorUserId());
    }

    @Test
    void blankHeaderActsAsNoHeader() {
        var delegation = new SubUserDelegationResolver().resolve(request("   "), MANAGE_KEY);

        assertFalse(delegation.delegated());
        assertEquals(MANAGER_ID, delegation.effectiveUserId());
    }

    @Test
    void callScopeKeyCannotDelegate() {
        var exception = assertThrows(ForbiddenException.class, () -> new SubUserDelegationResolver().resolve(request(SUB_USER_ID),
                new RequestAuthenticator.AuthResult(MANAGER_ID, KEY_ID, ApiUserKeyService.SCOPE_CALL)));

        assertEquals("X-Subuser-Id requires a management key", exception.getMessage());
    }

    @Test
    void unknownSubUserIsRejected() {
        var users = users();
        when(users.get(SUB_USER_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(ForbiddenException.class, () -> resolver(users).resolve(request(SUB_USER_ID), MANAGE_KEY));

        assertEquals("invalid sub user", exception.getMessage());
    }

    @Test
    void nonApiUserIsRejected() {
        var users = users();
        var internal = activeSubUser(MANAGER_ID);
        internal.userType = "internal";
        when(users.get(SUB_USER_ID)).thenReturn(Optional.of(internal));

        assertThrows(ForbiddenException.class, () -> resolver(users).resolve(request(SUB_USER_ID), MANAGE_KEY));
    }

    @Test
    void managerItselfIsRejected() {
        var users = users();
        var manager = activeSubUser(null);
        when(users.get(SUB_USER_ID)).thenReturn(Optional.of(manager));

        assertThrows(ForbiddenException.class, () -> resolver(users).resolve(request(SUB_USER_ID), MANAGE_KEY));
    }

    @Test
    void subUserOfAnotherManagerIsRejected() {
        var users = users();
        when(users.get(SUB_USER_ID)).thenReturn(Optional.of(activeSubUser("api:other-manager")));

        var exception = assertThrows(ForbiddenException.class, () -> resolver(users).resolve(request(SUB_USER_ID), MANAGE_KEY));

        assertEquals("sub user does not belong to current manager", exception.getMessage());
    }

    @Test
    void disabledSubUserIsRejected() {
        var users = users();
        var disabled = activeSubUser(MANAGER_ID);
        disabled.status = "disabled";
        when(users.get(SUB_USER_ID)).thenReturn(Optional.of(disabled));

        var exception = assertThrows(UnauthorizedException.class, () -> resolver(users).resolve(request(SUB_USER_ID), MANAGE_KEY));

        assertEquals("sub user disabled", exception.getMessage());
    }

    @Test
    void resolvesDelegationToOwnedActiveSubUser() {
        var users = users();
        when(users.get(SUB_USER_ID)).thenReturn(Optional.of(activeSubUser(MANAGER_ID)));

        var delegation = resolver(users).resolve(request(" " + SUB_USER_ID + " "), MANAGE_KEY);

        assertTrue(delegation.delegated());
        assertEquals(SUB_USER_ID, delegation.effectiveUserId());
        assertEquals(KEY_ID, delegation.keyId());
        assertEquals(MANAGER_ID, delegation.delegatorUserId());
    }

    private SubUserDelegationResolver resolver(MongoCollection<User> users) {
        var resolver = new SubUserDelegationResolver();
        resolver.userCollection = users;
        return resolver;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> users() {
        return (MongoCollection<User>) mock(MongoCollection.class);
    }

    private Request request(String subUserId) {
        var request = mock(Request.class);
        when(request.header(SubUserDelegationResolver.HEADER))
                .thenReturn(subUserId == null ? Optional.empty() : Optional.of(subUserId));
        return request;
    }

    private User activeSubUser(String ownerId) {
        var user = new User();
        user.id = SUB_USER_ID;
        user.userType = ApiUserService.USER_TYPE_API;
        user.ownerId = ownerId;
        user.status = "active";
        return user;
    }
}
