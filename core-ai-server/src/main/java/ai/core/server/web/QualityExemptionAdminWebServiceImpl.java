package ai.core.server.web;

import ai.core.api.server.QualityExemptionAdminWebService;
import ai.core.api.server.quality.AddQualityExemptionRequest;
import ai.core.api.server.quality.QualityExemptionListResponse;
import ai.core.api.server.quality.QualityExemptionSubjectListRequest;
import ai.core.api.server.quality.QualityExemptionSubjectListResponse;
import ai.core.api.server.quality.RemoveQualityExemptionRequest;
import ai.core.server.quality.QualityExemptionAdminService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author core-ai
 */
public class QualityExemptionAdminWebServiceImpl implements QualityExemptionAdminWebService {
    @Inject
    WebContext webContext;
    @Inject
    QualityExemptionAdminService adminService;

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public QualityExemptionListResponse list() {
        return adminService.list(AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public QualityExemptionSubjectListResponse subjects(QualityExemptionSubjectListRequest request) {
        return adminService.subjects(request, AuthContext.userId(webContext));
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public QualityExemptionListResponse add(AddQualityExemptionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("exemption_type", request == null ? null : request.type);
        ActionLogContext.put("subject_id", request == null ? null : request.subjectId);
        return adminService.add(request, userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.AGENT_QUALITY_MANAGE)
    public QualityExemptionListResponse remove(RemoveQualityExemptionRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        ActionLogContext.put("exemption_type", request == null ? null : request.type);
        ActionLogContext.put("exemption_value", request == null ? null : request.value);
        return adminService.remove(request, userId);
    }
}
