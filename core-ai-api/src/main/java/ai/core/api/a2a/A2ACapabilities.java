package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class A2ACapabilities {
    public static A2ACapabilities cliMode() {
        var caps = new A2ACapabilities();
        caps.chat = true;
        caps.authRequired = false;
        return caps;
    }

    public static A2ACapabilities serverMode() {
        var caps = new A2ACapabilities();
        caps.chat = true;
        caps.traces = true;
        caps.prompts = true;
        caps.dashboard = true;
        caps.authRequired = true;
        return caps;
    }

    @Property(name = "chat")
    public Boolean chat;

    @Property(name = "traces")
    public Boolean traces;

    @Property(name = "prompts")
    public Boolean prompts;

    @Property(name = "dashboard")
    public Boolean dashboard;

    @Property(name = "auth_required")
    public Boolean authRequired;
}
