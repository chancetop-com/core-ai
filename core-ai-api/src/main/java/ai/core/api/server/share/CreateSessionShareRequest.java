package ai.core.api.server.share;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class CreateSessionShareRequest {
    @Property(name = "expires_in_days")
    public Integer expiresInDays;
}
