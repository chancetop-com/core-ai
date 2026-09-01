package ai.core.api.server.sandbox;

import core.framework.api.json.Property;

/**
 * @author xander
 */
public class CreateTerminalResponse {
    @Property(name = "terminal_id")
    public String terminalId;

    @Property(name = "recovered")
    public Boolean recovered;
}
