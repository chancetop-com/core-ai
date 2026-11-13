package ai.core.api.server.share;

import ai.core.api.server.session.Message;
import core.framework.api.json.Property;
import java.util.List;

/**
 * @author Xander
 */
public class SharedSessionMessagesResponse {
    @Property(name = "messages")
    public List<Message> messages;

    @Property(name = "next_offset")
    public Integer nextOffset;
}
