package ai.core.agent;

import ai.core.persistence.Persistence;
import core.framework.api.json.Property;
import core.framework.json.JSON;

/**
 * @author stephen
 */
public class UserInputAgentPersistence implements Persistence<UserInputAgent> {
    @Override
    public String serialization(UserInputAgent agent) {
        var domain = UserInputAgentPersistenceDomain.of(agent.getInput(), agent.getNodeStatus());
        return JSON.toJSON(domain);
    }

    @Override
    public void deserialization(UserInputAgent agent, String t) {
        var domain = JSON.fromJSON(UserInputAgentPersistenceDomain.class, t);
        agent.setInput(domain.input);
        agent.updateNodeStatus(domain.status);
    }

    public static class UserInputAgentPersistenceDomain {

        public static UserInputAgentPersistenceDomain of(String input, NodeStatus status) {
            var domain = new UserInputAgentPersistenceDomain();
            domain.input = input;
            domain.status = status;
            return domain;
        }

        @Property(name = "input")
        public String input;

        @Property(name = "status")
        public NodeStatus status;
    }
}
