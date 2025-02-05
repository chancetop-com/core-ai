package ai.core.termination.terminations;

import ai.core.agent.Node;
import ai.core.termination.Termination;

/**
 * @author stephen
 */
public class UserCancelledTermination implements Termination {
    @Override
    public boolean terminate(Node<?> node) {
        return false;
    }
}
