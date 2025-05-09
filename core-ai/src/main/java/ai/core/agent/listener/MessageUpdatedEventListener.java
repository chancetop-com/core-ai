package ai.core.agent.listener;

import ai.core.agent.Node;
import ai.core.llm.providers.inner.LLMMessage;

/**
 * @author stephen
 */
public interface MessageUpdatedEventListener<T extends Node<T>> {
    void eventHandler(T t, LLMMessage message);
}
