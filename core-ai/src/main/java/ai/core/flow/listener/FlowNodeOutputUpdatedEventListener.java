package ai.core.flow.listener;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;

/**
 * @author stephen
 */
public interface FlowNodeOutputUpdatedEventListener {
    void eventHandler(FlowNode<?> node, String query, FlowNodeResult rst);
}
