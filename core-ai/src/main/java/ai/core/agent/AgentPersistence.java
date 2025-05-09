package ai.core.agent;

import ai.core.llm.providers.inner.LLMMessage;
import ai.core.persistence.Persistence;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public class AgentPersistence implements Persistence<Agent> {
    @Override
    public String serialization(Agent agent) {
        return JSON.toJSON(AgentPersistenceDomain.of(agent.getMessages()));
    }

    @Override
    public void deserialization(Agent agent, String context) {
        var domain = JSON.fromJSON(AgentPersistenceDomain.class, context);
        agent.addMessages(domain.messages);
    }

    public static class AgentPersistenceDomain {

        public static AgentPersistenceDomain of(List<LLMMessage> messages) {
            var domain = new AgentPersistenceDomain();
            domain.messages = messages;
            return domain;
        }

        @Property(name = "messages")
        public List<LLMMessage> messages;
    }
}
