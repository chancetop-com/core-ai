package ai.core.api.server.auth;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RegisterResponse {
    @Property(name = "api_key")
    public String apiKey;

    @Property(name = "user_id")
    public String userId;
}
