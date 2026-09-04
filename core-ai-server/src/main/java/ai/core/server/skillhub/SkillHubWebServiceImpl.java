package ai.core.server.skillhub;

import ai.core.api.server.SkillHubWebService;
import ai.core.api.server.skillhub.SkillHubDetail;
import ai.core.api.server.skillhub.SkillHubLookupRequest;
import ai.core.api.server.skillhub.SkillHubLookupResponse;
import ai.core.api.server.skillhub.SkillHubResourceRequest;
import ai.core.api.server.skillhub.SkillHubResourceResponse;
import ai.core.api.server.skillhub.SkillHubSearchRequest;
import ai.core.api.server.skillhub.SkillHubSearchResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class SkillHubWebServiceImpl implements SkillHubWebService {
    @Inject
    SkillHubService hubService;

    @Override
    @PermissionsRequired(PermissionCodes.SKILL_VIEW)
    public SkillHubSearchResponse search(SkillHubSearchRequest request) {
        var effective = request != null ? request : new SkillHubSearchRequest();
        return hubService.search(effective.query, effective.namespace, effective.sourceType, effective.limit);
    }

    @Override
    @PermissionsRequired(PermissionCodes.SKILL_VIEW)
    public SkillHubLookupResponse lookup(SkillHubLookupRequest request) {
        return hubService.lookup(request != null ? request.name : null);
    }

    @Override
    @PermissionsRequired(PermissionCodes.SKILL_VIEW)
    public SkillHubDetail show(String namespace, String name) {
        return hubService.show(namespace, name);
    }

    @Override
    @PermissionsRequired(PermissionCodes.SKILL_VIEW)
    public SkillHubResourceResponse resource(String namespace, String name, SkillHubResourceRequest request) {
        return hubService.resource(namespace, name, request != null ? request.path : null);
    }
}
