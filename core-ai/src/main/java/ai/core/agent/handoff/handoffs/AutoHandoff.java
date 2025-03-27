package ai.core.agent.handoff.handoffs;

import ai.core.agent.AgentGroup;
import ai.core.agent.AgentRole;
import ai.core.agent.handoff.Handoff;
import ai.core.agent.planning.Planning;
import ai.core.llm.providers.inner.Message;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AutoHandoff implements Handoff {
    @Override
    public void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables) {
        var currentAgent = agentGroup.getCurrentAgent();
        agentGroup.moderatorSpeaking();
        var text = planning.agentPlanning(agentGroup.getModerator(), agentGroup.getCurrentQuery(), variables);
        agentGroup.setRawOutput(agentGroup.getModerator().getRawOutput());
        agentGroup.addResponseChoiceMessages(List.of(
                Message.of(AgentRole.USER, currentAgent == null ? "user" : currentAgent.getName(), agentGroup.getCurrentQuery()),
                Message.of(AgentRole.ASSISTANT, text, agentGroup.getModerator().getName(), null, null, null)));
    }
}
