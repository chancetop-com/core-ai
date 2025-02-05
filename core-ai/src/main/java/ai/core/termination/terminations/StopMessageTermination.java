package ai.core.termination.terminations;

import ai.core.agent.Agent;
import ai.core.agent.Node;
import ai.core.termination.Termination;

/**
 * @author stephen
 */
public class StopMessageTermination implements Termination {
    @Override
    public boolean terminate(Node<?> node) {
        var agent = (Agent) node;
        return agent.getOutput().contains(DEFAULT_TERMINATION_WORD);
    }
}
