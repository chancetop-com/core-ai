package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ResourcePermissionRequest {
    @Property(name = "resource_type")
    public String resourceType;

    @Property(name = "resource_id")
    public String resourceId;
}
