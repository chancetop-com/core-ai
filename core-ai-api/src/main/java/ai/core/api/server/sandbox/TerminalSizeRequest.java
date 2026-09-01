package ai.core.api.server.sandbox;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author xander
 */
public class TerminalSizeRequest {
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
    @Property(name = "rows")
    public Integer rows;

    @NotNull
    @Property(name = "cols")
    public Integer cols;
}
