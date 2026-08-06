package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * Resource-level access permission for API users (resourceType + resourceId).
 * P1 supports resourceType=agent with concrete agent ids.
 *
 * @author stephen
 */
public class ResourcePermission {
    @Field(name = "resource_type")
    public String resourceType;

    @Field(name = "resource_id")
    public String resourceId;
}
