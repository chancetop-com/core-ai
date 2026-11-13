package ai.core.server.share;

import ai.core.server.domain.SessionShare;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Stores one generation per session; replacement and revocation compare the generation identifier.
 *
 * @author Xander
 */
public interface SessionShareStore {
    Optional<SessionShare> getBySessionId(String sessionId);

    Optional<SessionShare> getByToken(String token);

    boolean replace(SessionShare value, String expectedId);

    void revoke(String sessionId, String shareId, ZonedDateTime now);
}
