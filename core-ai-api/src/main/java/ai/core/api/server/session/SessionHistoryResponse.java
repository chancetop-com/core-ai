package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SessionHistoryResponse {
    @Property(name = "messages")
    public List<Message> messages;
}
