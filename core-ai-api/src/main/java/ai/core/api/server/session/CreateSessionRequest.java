package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateSessionRequest {
    @NotNull
    @Property(name = "config")
    public SessionConfig config;
}
