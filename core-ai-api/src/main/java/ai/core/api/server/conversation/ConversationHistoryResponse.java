package ai.core.api.server.conversation;

import ai.core.api.server.session.Message;
import ai.core.api.server.session.SessionArtifact;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ConversationHistoryResponse {
    @Property(name = "messages")
    public List<Message> messages;

    @Property(name = "artifacts")
    public List<SessionArtifact> artifacts;

    @Property(name = "has_more")
    public Boolean hasMore;

    @Property(name = "oldest_seq")
    public Long oldestSeq;
}
