package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class CreateKeyResponse {
    @NotNull
    @Property(name = "key_id")
    public String keyId;

    @NotNull
    @Property(name = "key")
    public String key;

    @Property(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Property(name = "metadata")
    public Map<String, String> metadata;
}
