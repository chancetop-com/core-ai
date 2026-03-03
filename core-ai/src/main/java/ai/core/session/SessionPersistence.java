package ai.core.session;

import ai.core.persistence.PersistenceProvider;

import java.time.Instant;
import java.util.List;

/**
 * @author stephen
 */
public interface SessionPersistence extends PersistenceProvider {
    record SessionInfo(String id, Instant lastModified) {}

    List<SessionInfo> listSessions();
}
