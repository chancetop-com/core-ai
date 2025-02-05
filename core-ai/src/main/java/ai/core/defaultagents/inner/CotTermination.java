package ai.core.defaultagents.inner;

import ai.core.agent.Agent;
import ai.core.agent.Node;
import ai.core.defaultagents.CotAgent;
import ai.core.termination.Termination;
import core.framework.json.JSON;

import java.util.Locale;

/**
 * @author stephen
 */
public class CotTermination implements Termination {
    @Override
    public boolean terminate(Node<?> node) {
        var agent = (Agent) node;
        var dto = JSON.fromJSON(CotAgent.CotResult.class, agent.getOutput().toLowerCase(Locale.ROOT));
        return terminateReflect(dto) || terminateByRound(agent) || terminateByConfidence(dto);
    }

    private boolean terminateByConfidence(CotAgent.CotResult dto) {
        return dto.steps.getLast().confidence > 0.9;
    }

    private boolean terminateReflect(CotAgent.CotResult dto) {
        return dto.steps.getLast().nextAction == CotAgent.CotResult.Action.TERMINATE;
    }

    private boolean terminateByRound(Agent agent) {
        return agent.getRound() >= agent.getReflectionConfig().maxRound();
    }
}
