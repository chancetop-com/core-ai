package ai.core.server.mcphub;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpHubAccessPolicyTest {
    private FakePermissionService permissionService;
    private McpHubAccessPolicy policy;

    @BeforeEach
    void setUp() {
        permissionService = new FakePermissionService();
        policy = new McpHubAccessPolicy();
        policy.permissionService = permissionService;
    }

    @Test
    void disabledEntryIsTreatedAsNotFound() {
        assertThrows(NotFoundException.class, () -> policy.checkCanCall("user-1", entry(false)));
    }

    @Test
    void internalUserOnlyNeedsEnabledEntry() {
        permissionService.apiUser = false;
        assertDoesNotThrow(() -> policy.checkCanCall("user-1", entry(true)));
    }

    @Test
    void apiUserWithoutWhitelistEntryIsForbidden() {
        permissionService.apiUser = true;
        permissionService.allowedResourceIds.clear();
        assertThrows(ForbiddenException.class, () -> policy.checkCanCall("api-user", entry(true)));
    }

    @Test
    void apiUserWithWhitelistEntryPasses() {
        permissionService.apiUser = true;
        permissionService.allowedResourceIds.add("srv-x");
        assertDoesNotThrow(() -> policy.checkCanCall("api-user", entry(true)));
    }

    private ToolRegistryEntry entry(boolean enabled) {
        var entry = new ToolRegistryEntry();
        entry.id = "srv-x";
        entry.name = "jira";
        entry.type = ToolType.MCP;
        entry.config = java.util.Map.of();
        entry.enabled = enabled;
        entry.createdAt = ZonedDateTime.now();
        return entry;
    }

    private static final class FakePermissionService extends PermissionService {
        boolean apiUser;
        final java.util.Set<String> allowedResourceIds = java.util.HashSet.newHashSet(4);

        @Override
        public boolean isApiUser(String userId) {
            return apiUser;
        }

        @Override
        public void check(String userId, String resourceType, String resourceId) {
            if (!allowedResourceIds.contains(resourceId)) {
                throw new ForbiddenException("no permission to access " + resourceType + " " + resourceId);
            }
        }
    }
}
