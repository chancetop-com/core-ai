package ai.core.api.server.sandbox;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author xander
 */
public class TerminalActivityRequest {
    @NotNull
    @NotBlank
    @Property(name = "session_id")
    public String sessionId;
}
