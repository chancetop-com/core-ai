package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * Partial update of a chat session: fields left null are not touched.
 *
 * @author stephen
 */
public class UpdateChatSessionRequest {
    @Property(name = "title")
    public String title;

    @Property(name = "notify_on_complete")
    public Boolean notifyOnComplete;
}
