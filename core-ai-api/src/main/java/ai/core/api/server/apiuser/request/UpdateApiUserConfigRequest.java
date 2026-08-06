package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Partial update of an API user's permissions and daily quota.
 * Fields are optional; only provided fields are updated.
 *
 * @author stephen
 */
public class UpdateApiUserConfigRequest {
    @Property(name = "permissions")
    public List<ResourcePermissionRequest> permissions;

    @Property(name = "token_quota")
    public Long tokenQuota;
}
