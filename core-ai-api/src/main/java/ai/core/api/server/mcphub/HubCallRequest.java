package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class HubCallRequest {
    /**
     * Tool arguments as a JSON object text (e.g. {@code {"project":"CORE","summary":"x"}}).
     * Text form avoids double encoding and keeps the view bean free of dynamic value types.
     */
    @Property(name = "arguments")
    public String arguments;

    @Property(name = "timeout_seconds")
    public Integer timeoutSeconds;
}
