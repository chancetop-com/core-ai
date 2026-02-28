package ai.core.api.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateSessionResponse {
    @NotNull
    @Property(name = "sessionId")
    public String sessionId;
}
