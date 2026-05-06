package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * Authentication details for push notification delivery.
 *
 * @author xander
 */
public class AuthenticationInfo {
    @Property(name = "scheme")
    public String scheme;

    @Property(name = "credentials")
    public String credentials;
}
