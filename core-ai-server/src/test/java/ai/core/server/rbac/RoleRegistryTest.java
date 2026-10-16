package ai.core.server.rbac;

import ai.core.server.domain.SystemSettings;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleRegistryTest {
    @Test
    void catalogContainsTheGatewayViewCode() {
        assertTrue(PermissionCodes.ALL.contains(PermissionCodes.GATEWAY_VIEW));
        assertTrue(PermissionCodes.ALL.contains(PermissionCodes.GATEWAY_MANAGE));
    }

    @Test
    void defaultUserRoleCanReadTheGatewayModelCatalog() {
        var permissions = registry().permissionsOf(RoleRegistry.ROLE_USER);

        assertTrue(permissions.contains(PermissionCodes.GATEWAY_VIEW));
        assertFalse(permissions.contains(PermissionCodes.GATEWAY_MANAGE));
    }

    @Test
    void memberRoleCannotReadTheGatewayModelCatalog() {
        assertFalse(registry().permissionsOf(RoleRegistry.ROLE_MEMBER).contains(PermissionCodes.GATEWAY_VIEW));
    }

    @Test
    void snapshotManagementIsSelectableWithoutGrantingItToDefaultRoles() {
        var registry = registry();

        assertTrue(PermissionCodes.ALL.contains(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE));
        assertFalse(registry.permissionsOf(RoleRegistry.ROLE_USER).contains(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE));
        assertFalse(registry.permissionsOf(RoleRegistry.ROLE_MEMBER).contains(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE));
        assertDoesNotThrow(() -> registry.updateRoles(Map.of("snapshot-operator", List.of(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE))));
    }

    private RoleRegistry registry() {
        var registry = new RoleRegistry();
        registry.systemSettingsCollection = systemSettingsCollection();
        return registry;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SystemSettings> systemSettingsCollection() {
        var collection = mock(MongoCollection.class);
        when(collection.get("default")).thenReturn(Optional.empty());
        return collection;
    }
}
