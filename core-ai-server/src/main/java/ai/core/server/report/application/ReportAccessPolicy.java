package ai.core.server.report.application;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.rbac.PermissionCodes;
import core.framework.inject.Inject;

/**
 * Single place for report owner/RBAC/dataset checks. Owner equality is still enforced by repository
 * queries; this policy answers "may this user perform the action at all".
 *
 * @author core-ai
 */
public class ReportAccessPolicy {
    @Inject
    PermissionService permissionService;

    public void requireView(String userId) {
        permissionService.check(userId, PermissionCodes.REPORT_VIEW);
    }

    public void requireManage(String userId) {
        permissionService.check(userId, PermissionCodes.REPORT_MANAGE);
    }

    public void requireDatasetView(String userId) {
        permissionService.check(userId, PermissionCodes.DATASET_VIEW);
    }

    public void requirePin(String userId) {
        permissionService.check(userId, PermissionCodes.DASHBOARD_VIEW);
        permissionService.check(userId, PermissionCodes.REPORT_VIEW);
    }

    public boolean canManage(String userId) {
        return permissionService.has(userId, PermissionCodes.REPORT_MANAGE);
    }

    public boolean canView(String userId) {
        return permissionService.has(userId, PermissionCodes.REPORT_VIEW);
    }

    public boolean canViewDataset(String userId) {
        return permissionService.has(userId, PermissionCodes.DATASET_VIEW);
    }
}
