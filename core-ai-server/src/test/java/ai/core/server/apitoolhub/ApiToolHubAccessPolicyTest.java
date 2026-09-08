package ai.core.server.apitoolhub;

import ai.core.server.apiuser.PermissionService;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiToolHubAccessPolicyTest {
    private PermissionService permissionService;
    private ApiToolHubAccessPolicy policy;

    @BeforeEach
    void setUp() {
        permissionService = mock(PermissionService.class);
        policy = new ApiToolHubAccessPolicy();
        policy.permissionService = permissionService;
    }

    @Test
    void internalUserSkipsAppWhitelist() {
        when(permissionService.isApiUser("internal-1")).thenReturn(Boolean.FALSE);
        policy.checkCanCall("internal-1", "order-service");
        assertTrue(policy.canAccessApp("internal-1", "order-service"));
        verify(permissionService, never()).checkResource(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void apiUserWithoutWhitelistEntryIsForbidden() {
        when(permissionService.isApiUser("api-1")).thenReturn(Boolean.TRUE);
        when(permissionService.checkResource("api-1", PermissionService.RESOURCE_TYPE_API_APP, "order-service"))
                .thenReturn(Boolean.FALSE);
        org.mockito.Mockito.doThrow(new ForbiddenException("denied"))
                .when(permissionService).check("api-1", PermissionService.RESOURCE_TYPE_API_APP, "order-service");
        assertThrows(ForbiddenException.class, () -> policy.checkCanCall("api-1", "order-service"));
        assertFalse(policy.canAccessApp("api-1", "order-service"));
    }

    @Test
    void apiUserWithWhitelistEntryPasses() {
        when(permissionService.isApiUser("api-1")).thenReturn(Boolean.TRUE);
        when(permissionService.checkResource("api-1", PermissionService.RESOURCE_TYPE_API_APP, "order-service"))
                .thenReturn(Boolean.TRUE);
        policy.checkCanCall("api-1", "order-service");
        assertTrue(policy.canAccessApp("api-1", "order-service"));
        verify(permissionService).check("api-1", PermissionService.RESOURCE_TYPE_API_APP, "order-service");
    }

    @Test
    void nullUserIdIsUnrestricted() {
        assertTrue(policy.canAccessApp(null, "order-service"));
    }
}
