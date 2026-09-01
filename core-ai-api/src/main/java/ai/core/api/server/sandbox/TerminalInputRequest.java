package ai.core.api.server.sandbox;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author xander
 */
public class TerminalInputRequest {
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
    @Property(name = "terminal_id")
    public String terminalId;

    @NotNull
    @NotBlank
    @Property(name = "data_base64")
    public String dataBase64;
}
