package ai.core.termination;

import ai.core.agent.Node;

/**
 * @author stephen
 */
public interface Termination {
    String DEFAULT_TERMINATION_WORD = "TERMINATE";
    boolean terminate(Node<?> node);
}
