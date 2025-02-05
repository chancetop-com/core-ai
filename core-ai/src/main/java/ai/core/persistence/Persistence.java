package ai.core.persistence;

import ai.core.agent.Node;

/**
 * @author stephen
 */
public interface Persistence<T extends Node<T>> {
    String serialization(T t);

    void deserialization(T t, String c);
}
