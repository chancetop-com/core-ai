package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ResourcePermissionView {
    @Property(name = "resource_type")
    public String resourceType;

    @Property(name = "resource_id")
    public String resourceId;
}
