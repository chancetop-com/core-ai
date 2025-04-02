package ai.core.persistence;

/**
 * @author stephen
 */
public interface Persistence<T> {
    String serialization(T t);

    void deserialization(T t, String c);
}
