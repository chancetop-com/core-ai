package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateChatSessionResponse {
    @Property(name = "updated")
    public Boolean updated;

    /**
     * Whether the session owner has a notification target configured. The chat switch uses it to
     * warn when it is turned on with nowhere to deliver.
     */
    @Property(name = "notify_target_configured")
    public Boolean notifyTargetConfigured;
}
