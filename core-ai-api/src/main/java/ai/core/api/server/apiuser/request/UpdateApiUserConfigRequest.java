package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Partial update of an API user's permissions and daily quota.
 * Fields are optional; only provided fields are updated.
 * Quota unit is tokens (1M = 1_000_000).
 *
 * @author stephen
 */
public class UpdateApiUserConfigRequest {
    @Property(name = "permissions")
    public List<ResourcePermissionRequest> permissions;

    @Property(name = "input_token_quota")
    public Long inputTokenQuota;

    @Property(name = "output_token_quota")
    public Long outputTokenQuota;
}
