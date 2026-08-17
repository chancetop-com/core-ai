package ai.core.server.seoops;

import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * @author xander
 */
class SeoOpsPermissionContractTest {
    @Test
    void everyEndpointHasItsRequiredPermission() {
        var expected = Map.ofEntries(
            Map.entry("config", PermissionCodes.SEOOPS_VIEW),
            Map.entry("portfolio", PermissionCodes.SEOOPS_VIEW),
            Map.entry("inbox", PermissionCodes.SEOOPS_VIEW),
            Map.entry("reviews", PermissionCodes.SEOOPS_VIEW),
            Map.entry("reports", PermissionCodes.SEOOPS_VIEW),
            Map.entry("task", PermissionCodes.SEOOPS_VIEW),
            Map.entry("events", PermissionCodes.SEOOPS_VIEW),
            Map.entry("createMerchant", PermissionCodes.SEOOPS_MANAGE),
            Map.entry("createLocation", PermissionCodes.SEOOPS_MANAGE),
            Map.entry("createTask", PermissionCodes.SEOOPS_MANAGE),
            Map.entry("createRevision", PermissionCodes.SEOOPS_MANAGE),
            Map.entry("appendEvidence", PermissionCodes.SEOOPS_MANAGE),
            Map.entry("linkConversation", PermissionCodes.SEOOPS_MANAGE),
            Map.entry("approvalPreview", PermissionCodes.SEOOPS_APPROVE),
            Map.entry("approvalDecision", PermissionCodes.SEOOPS_APPROVE));
        for (var method : SeoOpsWebServiceImpl.class.getDeclaredMethods()) {
            if (!expected.containsKey(method.getName())) continue;
            var annotation = method.getAnnotation(PermissionsRequired.class);
            assertArrayEquals(new String[]{expected.get(method.getName())}, annotation.value(), method.getName());
        }
    }
}
