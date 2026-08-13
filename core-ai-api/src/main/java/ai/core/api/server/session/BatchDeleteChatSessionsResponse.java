package ai.core.api.server.session;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class BatchDeleteChatSessionsResponse {
    @Property(name = "deleted")
    public Integer deleted;
}
