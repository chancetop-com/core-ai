package ai.core.server.seoops;

import ai.core.server.rbac.PermissionCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SeoOpsPermissionCatalogTest {
    @Test
    void exposesViewManageAndApprovePermissions() {
        assertTrue(PermissionCodes.ALL.contains("seoops.view"));
        assertTrue(PermissionCodes.ALL.contains("seoops.manage"));
        assertTrue(PermissionCodes.ALL.contains("seoops.approve"));
    }
}
