package ai.core.flow.listener;

import ai.core.flow.FlowNode;

/**
 * @author stephen
 */
public interface FlowNodeChangedEventListener {
    void eventHandler(FlowNode<?> node);
}
