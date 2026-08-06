package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class RenewKeyResponse {
    @NotNull
    @Property(name = "key_id")
    public String keyId;

    @Property(name = "expires_at")
    public ZonedDateTime expiresAt;
}
