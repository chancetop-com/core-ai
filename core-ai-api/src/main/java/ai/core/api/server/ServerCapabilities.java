package ai.core.api.server;

import core.framework.api.json.Property;

/**
 * Web-only capabilities response for GET /api/capabilities.
 * Deliberately decoupled from A2ACapabilities so web feature flags never leak
 * into the A2A agent card.
 */
public class ServerCapabilities {
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
    @Property(name = "sandbox_terminal_enabled")
    public Boolean sandboxTerminalEnabled;
}
