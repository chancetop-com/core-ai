package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DeleteChatSessionResponse {
    @Property(name = "deleted")
    public Boolean deleted;
}
