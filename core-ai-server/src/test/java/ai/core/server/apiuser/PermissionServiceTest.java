package ai.core.server.apiuser;

import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.RoleRegistry;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionServiceTest {
    @Test
    void manageImpliesView() {
        var service = service("ops", List.of(PermissionCodes.GATEWAY_MANAGE));

        assertTrue(service.has("u1", PermissionCodes.GATEWAY_VIEW));
    }

    @Test
    void manageImpliesCall() {
        var service = service("ops", List.of(PermissionCodes.APITOOL_MANAGE));

        assertTrue(service.has("u1", PermissionCodes.APITOOL_CALL));
    }

    @Test
    void implicationStaysInsideItsDomain() {
        var service = service("ops", List.of(PermissionCodes.GATEWAY_MANAGE));

        assertFalse(service.has("u1", PermissionCodes.MCP_VIEW));
        assertFalse(service.has("u1", "gateway.images"));
    }

    @Test
    void viewDoesNotImplyManage() {
        var service = service("ops", List.of(PermissionCodes.GATEWAY_VIEW));

        assertFalse(service.has("u1", PermissionCodes.GATEWAY_MANAGE));
    }

    @Test
    void adminIsAWildcard() {
        var service = service("admin", List.of(RoleRegistry.ALL_PERMISSIONS));

        assertTrue(service.has("u1", PermissionCodes.GATEWAY_VIEW));
        assertTrue(service.has("u1", PermissionCodes.GATEWAY_MANAGE));
    }

    private PermissionService service(String role, List<String> permissions) {
        var service = new PermissionService();
        service.userCollection = userCollection(role);
        var registry = mock(RoleRegistry.class);
        when(registry.permissionsOf(role)).thenReturn(permissions);
        service.roleRegistry = registry;
        return service;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> userCollection(String role) {
        var collection = mock(MongoCollection.class);
        var user = new User();
        user.id = "u1";
        user.role = role;
        when(collection.get("u1")).thenReturn(Optional.of(user));
        return collection;
    }
}
