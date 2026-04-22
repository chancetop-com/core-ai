package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author stephen
 */
public class SendMessageRequest {
    @NotNull
    @Property(name = "message")
    public String message;

    @Property(name = "variables")
    public Map<String, String> variables;
}
