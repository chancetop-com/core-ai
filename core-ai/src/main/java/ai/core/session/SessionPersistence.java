package ai.core.session;

import ai.core.persistence.PersistenceProvider;

import java.time.Instant;
import java.util.List;

/**
 * @author stephen
 */
public interface SessionPersistence extends PersistenceProvider {
    List<SessionInfo> listSessions();

    default boolean exists(String id) {
        return load(id).isPresent();
    }

    record SessionInfo(String id, Instant lastModified) { }
}
