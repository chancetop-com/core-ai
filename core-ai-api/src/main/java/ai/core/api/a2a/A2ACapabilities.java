package ai.core.api.a2a;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class A2ACapabilities {
    public static A2ACapabilities cliMode() {
        var caps = new A2ACapabilities();
        caps.chat = Boolean.TRUE;
        caps.authRequired = Boolean.FALSE;
        return caps;
    }

    public static A2ACapabilities serverMode() {
        var caps = new A2ACapabilities();
        caps.chat = Boolean.TRUE;
        caps.traces = Boolean.TRUE;
        caps.prompts = Boolean.TRUE;
        caps.dashboard = Boolean.TRUE;
        caps.authRequired = Boolean.TRUE;
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
