package ai.core.server.sandbox.snapshot;

import java.util.Optional;

/**
 * @author stephen
 */
public interface SandboxEpochStore {
    Optional<SandboxEpochDoc> get(String sessionId);

    boolean insertIfAbsent(SandboxEpochDoc doc);

    boolean compareAndIncrement(String sessionId, long current, long next);
}
