package ai.core.agent.listener;

import ai.core.agent.Node;
import ai.core.llm.domain.Message;

/**
 * @author stephen
 */
public interface MessageUpdatedEventListener<T extends Node<T>> {
    void eventHandler(T t, Message message);
}
