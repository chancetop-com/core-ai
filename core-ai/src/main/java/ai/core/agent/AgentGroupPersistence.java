package ai.core.agent;

import ai.core.llm.providers.inner.Message;
import ai.core.persistence.Persistence;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public class AgentGroupPersistence implements Persistence<AgentGroup> {
    @Override
    public String serialization(AgentGroup chainNode) {
        return JSON.toJSON(AgentGroupPersistenceDomain.of(chainNode.getMessages(), chainNode.moderator.getMessages()));
    }

    @Override
    public void deserialization(AgentGroup group, String t) {
        var domain = JSON.fromJSON(AgentGroupPersistenceDomain.class, t);
        group.addMessages(domain.messages);
        group.moderator.addMessages(domain.moderatorMessages);
    }

    public static class AgentGroupPersistenceDomain {
        public static AgentGroupPersistenceDomain of(List<Message> messages, List<Message> moderatorMessages) {
            var domain = new AgentGroupPersistenceDomain();
            domain.messages = messages;
            domain.moderatorMessages = moderatorMessages;
            return domain;
        }

        @Property(name = "messages")
        public List<Message> messages;

        @Property(name = "moderator_messages")
        public List<Message> moderatorMessages;
    }
}
