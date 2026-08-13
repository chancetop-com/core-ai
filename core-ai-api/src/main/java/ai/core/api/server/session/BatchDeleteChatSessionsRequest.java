package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class BatchDeleteChatSessionsRequest {
    @Property(name = "session_ids")
    public List<String> sessionIds;
}
