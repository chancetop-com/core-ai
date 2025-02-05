package ai.core.agent.listener;

import ai.core.agent.Node;

/**
 * @author stephen
 */
public interface ChainNodeStatusChangedEventListener<T extends Node<T>> {
    void eventHandler(T t);
}
