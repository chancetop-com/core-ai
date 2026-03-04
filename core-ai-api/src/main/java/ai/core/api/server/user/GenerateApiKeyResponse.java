package ai.core.api.server.user;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GenerateApiKeyResponse {
    @Property(name = "api_key")
    public String apiKey;
}
