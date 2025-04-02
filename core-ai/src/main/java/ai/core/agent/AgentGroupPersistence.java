package ai.core.agent;

import ai.core.agent.handoff.handoffs.AutoHandoff;
import ai.core.agent.handoff.handoffs.HybridAutoDirectHandoff;
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
    public String serialization(AgentGroup agent) {
        var domain = AgentGroupPersistenceDomain.of(agent.getMessages());
        if (agent.handoff instanceof AutoHandoff handoff) {
            domain.moderatorMessages = handoff.moderator().getMessages();
        }
        if (agent.handoff instanceof HybridAutoDirectHandoff handoff) {
            domain.moderatorMessages = handoff.getAutoHandoff().moderator().getMessages();
        }
        return JSON.toJSON(domain);
    }

    @Override
    public void deserialization(AgentGroup agent, String t) {
        var domain = JSON.fromJSON(AgentGroupPersistenceDomain.class, t);
        agent.addMessages(domain.messages);
        if (agent.handoff instanceof AutoHandoff handoff) {
            handoff.moderator().addMessages(domain.moderatorMessages);
        }
        if (agent.handoff instanceof HybridAutoDirectHandoff handoff) {
            handoff.getAutoHandoff().moderator().addMessages(domain.moderatorMessages);
        }
    }

    public static class AgentGroupPersistenceDomain {
        public static AgentGroupPersistenceDomain of(List<Message> messages) {
            var domain = new AgentGroupPersistenceDomain();
            domain.messages = messages;
            return domain;
        }

        @Property(name = "messages")
        public List<Message> messages;

        @Property(name = "moderator_messages")
        public List<Message> moderatorMessages;
    }
}
