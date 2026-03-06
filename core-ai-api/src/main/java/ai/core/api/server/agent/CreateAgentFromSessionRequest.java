package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateAgentFromSessionRequest {
    @NotNull
    @Property(name = "session_id")
    public String sessionId;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "input_template")
    public String inputTemplate;
}
