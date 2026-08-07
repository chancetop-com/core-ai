package ai.core.server.apiuser;

import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Idempotency of createApiUser: business systems may retry creation for the same merchant,
 * duplicates must collapse onto a single user document.
 *
 * @author stephen
 */
class ApiUserServiceTest {
    private final MongoCollection<User> userCollection;
    private final ApiUserService service;

    ApiUserServiceTest() {
        @SuppressWarnings("unchecked")
        MongoCollection<User> collection = mock(MongoCollection.class);
        userCollection = collection;
        service = new ApiUserService();
        service.userCollection = userCollection;
    }

    @Test
    void repeatedCreateReturnsExistingUserWithoutInsert() {
        var existing = user("api:1", "owner-1", "merchant-1");
        when(userCollection.find(any(Bson.class))).thenReturn(List.of(existing));

        var result = service.createApiUser("owner-1", "merchant-1", "Merchant One");

        assertEquals("api:1", result.id);
        verify(userCollection, never()).insert(any(User.class));
    }

    @Test
    void concurrentCreateWithSameKeyReturnsWinner() {
        var winner = user("api:2", "owner-1", "merchant-1");
        // both callers see no existing user, then one insert hits the unique index conflict,
        // the loser re-fetches and returns the winner
        var findCalls = new AtomicInteger();
        when(userCollection.find(any(Bson.class))).thenAnswer(inv -> findCalls.getAndIncrement() == 0 ? List.of() : List.of(winner));
        doThrow(new RuntimeException("duplicate key")).when(userCollection).insert(any(User.class));

        var result = service.createApiUser("owner-1", "merchant-1", "Merchant One");

        assertEquals("api:2", result.id);
    }

    @Test
    void concurrentCreateWithoutWinnerPropagatesInsertError() {
        when(userCollection.find(any(Bson.class))).thenAnswer(inv -> List.of());

        doThrow(new RuntimeException("duplicate key")).when(userCollection).insert(any(User.class));

        assertThrows(RuntimeException.class,
            () -> service.createApiUser("owner-1", "merchant-1", "Merchant One"));
    }

    @Test
    void externalIdIsTrimmedBeforeLookup() {
        var existing = user("api:3", "owner-1", "merchant-1");
        when(userCollection.find(any(Bson.class))).thenReturn(List.of(existing));

        var result = service.createApiUser("owner-1", "  merchant-1  ", "Merchant One");

        assertEquals("api:3", result.id);
        verify(userCollection, never()).insert(any(User.class));
    }

    @Test
    void blankExternalIdRejected() {
        assertThrows(RuntimeException.class,
            () -> service.createApiUser("owner-1", "   ", "Merchant One"));
    }

    private User user(String id, String ownerId, String externalId) {
        var user = new User();
        user.id = id;
        user.ownerId = ownerId;
        user.externalId = externalId;
        return user;
    }
}
