package ai.core.api.server.sandbox;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author xander
 */
public class TerminalTicketRequest {
    @NotNull
    @NotBlank
    @Property(name = "session_id")
    public String sessionId;

    @NotNull
    @NotBlank
    @Property(name = "sandbox_id")
    public String sandboxId;

    @NotNull
    @NotBlank
    @Property(name = "client_id")
    public String clientId;
}
