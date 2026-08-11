package ai.core.server.apiuser;

import ai.core.api.server.apiuser.request.OutboundCallerHeaderRequest;
import ai.core.api.server.apiuser.request.UpdateApiUserConfigRequest;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        var result = service.createApiUser("owner-1", "merchant-1", "Merchant One", null);

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

        var result = service.createApiUser("owner-1", "merchant-1", "Merchant One", null);

        assertEquals("api:2", result.id);
    }

    @Test
    void concurrentCreateWithoutWinnerPropagatesInsertError() {
        when(userCollection.find(any(Bson.class))).thenAnswer(inv -> List.of());

        doThrow(new RuntimeException("duplicate key")).when(userCollection).insert(any(User.class));

        assertThrows(RuntimeException.class,
            () -> service.createApiUser("owner-1", "merchant-1", "Merchant One", null));
    }

    @Test
    void externalIdIsTrimmedBeforeLookup() {
        var existing = user("api:3", "owner-1", "merchant-1");
        when(userCollection.find(any(Bson.class))).thenReturn(List.of(existing));

        var result = service.createApiUser("owner-1", "  merchant-1  ", "Merchant One", null);

        assertEquals("api:3", result.id);
        verify(userCollection, never()).insert(any(User.class));
    }

    @Test
    void blankExternalIdRejected() {
        assertThrows(RuntimeException.class,
            () -> service.createApiUser("owner-1", "   ", "Merchant One", null));
    }

    @Test
    void metadataStoredOnCreate() {
        when(userCollection.find(any(Bson.class))).thenReturn(List.of());

        var result = service.createApiUser("owner-1", "merchant-1", "Merchant One",
                Map.of("store_id", " 888 ", "region", "us-east"));

        assertEquals("888", result.metadata.get("store_id"));
        assertEquals("us-east", result.metadata.get("region"));
    }

    @Test
    void emptyMetadataNotStored() {
        when(userCollection.find(any(Bson.class))).thenReturn(List.of());

        var result = service.createApiUser("owner-1", "merchant-1", "Merchant One", Map.of());

        assertNull(result.metadata);
    }

    @Test
    void adminCanConfigureCallerHeadersOnManager() {
        var admin = new User();
        admin.id = "admin-1";
        admin.role = "admin";
        var manager = user("api:manager", null, null);
        manager.userType = "api";
        when(userCollection.get("admin-1")).thenReturn(java.util.Optional.of(admin));
        when(userCollection.get("api:manager")).thenReturn(java.util.Optional.of(manager));

        var request = new UpdateApiUserConfigRequest();
        var header = new OutboundCallerHeaderRequest();
        header.headerName = "X-MERCHANT-ID";
        header.valueSource = "external_id";
        request.outboundCallerHeaders = List.of(header);

        var result = service.updateConfigByAdmin("admin-1", "api:manager", request);

        assertEquals(1, result.outboundCallerHeaders.size());
        assertEquals("X-MERCHANT-ID", result.outboundCallerHeaders.getFirst().headerName);
    }

    @Test
    void adminConfigOnManagerRejectsSubUserFields() {
        var admin = new User();
        admin.id = "admin-1";
        admin.role = "admin";
        var manager = user("api:manager", null, null);
        manager.userType = "api";
        when(userCollection.get("admin-1")).thenReturn(java.util.Optional.of(admin));
        when(userCollection.get("api:manager")).thenReturn(java.util.Optional.of(manager));

        var request = new UpdateApiUserConfigRequest();
        request.permissions = List.of();

        assertThrows(core.framework.web.exception.BadRequestException.class,
            () -> service.updateConfigByAdmin("admin-1", "api:manager", request));
    }

    @Test
    void managerCannotConfigureCallerHeadersOnSubUser() {
        var subUser = user("api:sub", "api:manager", "merchant-1");
        subUser.userType = "api";
        when(userCollection.get("api:sub")).thenReturn(java.util.Optional.of(subUser));

        var request = new UpdateApiUserConfigRequest();
        var header = new OutboundCallerHeaderRequest();
        header.headerName = "X-MERCHANT-ID";
        header.valueSource = "external_id";
        request.outboundCallerHeaders = List.of(header);

        assertThrows(core.framework.web.exception.BadRequestException.class,
            () -> service.updateConfig("api:manager", "api:sub", request));
    }

    private User user(String id, String ownerId, String externalId) {
        var user = new User();
        user.id = id;
        user.ownerId = ownerId;
        user.externalId = externalId;
        return user;
    }
}
