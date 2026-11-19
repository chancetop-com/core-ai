package ai.core.server.home;

import ai.core.server.domain.HomeTodoDigestRun;

import java.util.Optional;

/**
 * @author core-ai
 */
public interface HomeTodoDigestStore {
    Optional<HomeTodoDigestRun> get(String id);

    /** @return false when another replica already claimed this user/day */
    boolean insertIfAbsent(HomeTodoDigestRun run);

    void replace(HomeTodoDigestRun run);
}
