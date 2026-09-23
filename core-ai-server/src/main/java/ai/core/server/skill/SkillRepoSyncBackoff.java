package ai.core.server.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backs off a repo that keeps failing, so an unreachable GitHub is not re-cloned (and logged) on every
 * scheduled run. State is per server instance and resets on restart.
 *
 * @author stephen
 */
class SkillRepoSyncBackoff {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillRepoSyncBackoff.class);
    private static final Duration BASE_DELAY = Duration.ofMinutes(30);
    private static final Duration MAX_DELAY = Duration.ofHours(4);

    static Duration delay(int failures) {
        var delay = BASE_DELAY;
        for (int i = 1; i < failures && delay.compareTo(MAX_DELAY) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    boolean deferred(String repoUrl) {
        var attempt = repoUrl == null ? null : attempts.get(repoUrl);
        if (attempt == null || !attempt.retryAt().isAfter(Instant.now())) return false;
        LOGGER.info("skill repo sync deferred, repo={}, failures={}, retryAt={}", repoUrl, attempt.failures(), attempt.retryAt());
        return true;
    }

    void failed(String repoUrl) {
        if (repoUrl == null) return;
        var attempt = attempts.compute(repoUrl, (key, previous) -> {
            int failures = previous == null ? 1 : previous.failures() + 1;
            return new Attempt(failures, Instant.now().plus(delay(failures)));
        });
        LOGGER.info("skill repo sync failed {} time(s), next attempt at {}", attempt.failures(), attempt.retryAt());
    }

    void succeeded(String repoUrl) {
        if (repoUrl != null) attempts.remove(repoUrl);
    }

    private record Attempt(int failures, Instant retryAt) {
    }
}
