package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author stephen
 */
public class CreateApiUserRequest {
    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "external_id")
    public String externalId;

    @Property(name = "metadata")
    public Map<String, String> metadata;
}
