package ai.core.termination.terminations;

import ai.core.agent.Node;
import ai.core.termination.Termination;

/**
 * @author stephen
 */
public class MaxRoundTermination implements Termination {
    @Override
    public boolean terminate(Node<?> node) {
        return node.getRound() > node.getMaxRound();
    }
}
