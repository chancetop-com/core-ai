package ai.core.server.rbac;

import ai.core.server.domain.SystemSettings;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void traceScopeDefaultsToSelfAndAdminIsImplicitlyAll() {
        var registry = registry();

        assertEquals(TraceScopeMode.SELF, registry.traceScope(RoleRegistry.ROLE_USER));
        assertEquals(TraceScopeMode.ALL, registry.traceScope(RoleRegistry.ROLE_ADMIN));
        assertEquals("self", registry.traceScopes().get(RoleRegistry.ROLE_USER));
        assertFalse(registry.traceScopes().containsKey(RoleRegistry.ROLE_ADMIN));
    }

    @Test
    void updateRolesStoresNonSelfTraceScopesAndEchoesThem() {
        var entity = new SystemSettings();
        entity.id = "default";
        var collection = systemSettingsCollection();
        when(collection.get("default")).thenReturn(Optional.of(entity));
        var registry = registry(collection);

        registry.updateRoles(
                Map.of("developer", List.of(PermissionCodes.TRACE_VIEW), "member", List.of()),
                Map.of("developer", "api", "member", "self"));

        assertEquals(TraceScopeMode.API, registry.traceScope("developer"));
        assertEquals(TraceScopeMode.SELF, registry.traceScope("member"));
        assertEquals("api", registry.traceScopes().get("developer"));
        assertEquals("self", registry.traceScopes().get("member"));
        assertTrue(entity.rbacTraceScopes.contains("\"developer\":\"api\""));
        assertFalse(entity.rbacTraceScopes.contains("member"));
    }

    @Test
    void updateRolesKeepsTraceScopesWhenNotProvided() {
        var entity = new SystemSettings();
        entity.id = "default";
        entity.rbacTraceScopes = "{\"developer\":\"api\"}";
        var collection = systemSettingsCollection();
        when(collection.get("default")).thenReturn(Optional.of(entity));
        var registry = registry(collection);

        registry.updateRoles(Map.of("developer", List.of(PermissionCodes.TRACE_VIEW)));

        assertEquals("{\"developer\":\"api\"}", entity.rbacTraceScopes);
        assertEquals(TraceScopeMode.API, registry.traceScope("developer"));
    }

    @Test
    void updateRolesRejectsInvalidTraceScopes() {
        var registry = registry();

        assertThrows(BadRequestException.class,
                () -> registry.updateRoles(Map.of("developer", List.of()), Map.of("developer", "everything")));
        assertThrows(BadRequestException.class,
                () -> registry.updateRoles(Map.of("developer", List.of()), Map.of(RoleRegistry.ROLE_ADMIN, "all")));
    }

    @Test
    void unknownStoredTraceScopeDegradesToSelf() {
        var entity = new SystemSettings();
        entity.id = "default";
        entity.rbacTraceScopes = "{\"developer\":\"everything\"}";
        var collection = systemSettingsCollection();
        when(collection.get("default")).thenReturn(Optional.of(entity));

        assertEquals(TraceScopeMode.SELF, registry(collection).traceScope("developer"));
    }

    private RoleRegistry registry() {
        return registry(systemSettingsCollection());
    }

    private RoleRegistry registry(MongoCollection<SystemSettings> collection) {
        var registry = new RoleRegistry();
        registry.systemSettingsCollection = collection;
        return registry;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<SystemSettings> systemSettingsCollection() {
        var collection = mock(MongoCollection.class);
        when(collection.get("default")).thenReturn(Optional.empty());
        return collection;
    }
}
