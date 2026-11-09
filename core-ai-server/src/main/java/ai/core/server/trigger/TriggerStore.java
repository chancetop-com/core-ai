package ai.core.server.trigger;

import ai.core.server.trigger.domain.Trigger;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface TriggerStore {
    List<Trigger> list(String type);

    Optional<Trigger> get(String id);

    void insert(Trigger trigger);

    void replace(Trigger trigger);

    void delete(String id);
}
