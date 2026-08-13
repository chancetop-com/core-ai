package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SubmitSessionFeedbackResponse {
    @Property(name = "id")
    public String id;

    @Property(name = "created")
    public Boolean created;
}
