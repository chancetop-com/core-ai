package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateApiUserResponse {
    @NotNull
    @Property(name = "user_id")
    public String userId;

    @Property(name = "api_key")
    public String apiKey;

    @NotNull
    @Property(name = "status")
    public String status;
}
