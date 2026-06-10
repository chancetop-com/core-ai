package ai.core.api.server.auth;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

public class RevokeApiKeyRequest {
    @NotNull
    @Property(name = "email")
    public String email;
}
