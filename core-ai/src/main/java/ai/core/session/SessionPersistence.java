package ai.core.session;

import ai.core.persistence.PersistenceProvider;

import java.time.Instant;
import java.util.List;

/**
 * @author stephen
 */
public interface SessionPersistence extends PersistenceProvider {
    List<SessionInfo> listSessions();

    record SessionInfo(String id, Instant lastModified) { }
}
