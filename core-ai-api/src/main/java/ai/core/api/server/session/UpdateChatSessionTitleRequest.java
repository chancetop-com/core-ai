package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateChatSessionTitleRequest {
    @Property(name = "title")
    public String title;
}
