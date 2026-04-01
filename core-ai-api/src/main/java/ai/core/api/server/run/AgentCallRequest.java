package ai.core.api.server.run;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author Xander
 */
public class AgentCallRequest {
    @NotNull
    @Property(name = "input")
    public String input;
}
