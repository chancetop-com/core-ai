package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Webhook configuration for asynchronous A2A task updates.
 *
 * @author xander
 */
public class PushNotificationConfig {
    @Property(name = "url")
    public String url;

    @Property(name = "token")
    public String token;

    @Property(name = "authentication")
    public AuthenticationInfo authentication;

    @Property(name = "metadata")
    public Map<String, Object> metadata;
}
