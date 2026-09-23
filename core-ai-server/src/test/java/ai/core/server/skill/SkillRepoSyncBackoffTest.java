package ai.core.server.skill;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRepoSyncBackoffTest {
    private static final String REPO_URL = "https://github.com/wuyoscar/GPT-Image2-Skill";

    @Test
    void defersAFailingRepoUntilItsRetryTimeAndClearsOnSuccess() {
        var backoff = new SkillRepoSyncBackoff();
        assertFalse(backoff.deferred(REPO_URL));

        backoff.failed(REPO_URL);

        assertTrue(backoff.deferred(REPO_URL));
        assertFalse(backoff.deferred("https://github.com/other/repo"));

        backoff.succeeded(REPO_URL);
        assertFalse(backoff.deferred(REPO_URL));
    }

    @Test
    void delayGrowsWithFailuresAndStopsAtTheCap() {
        assertEquals(Duration.ofMinutes(30), SkillRepoSyncBackoff.delay(1));
        assertEquals(Duration.ofHours(1), SkillRepoSyncBackoff.delay(2));
        assertEquals(Duration.ofHours(2), SkillRepoSyncBackoff.delay(3));
        assertEquals(Duration.ofHours(4), SkillRepoSyncBackoff.delay(4));
        assertEquals(Duration.ofHours(4), SkillRepoSyncBackoff.delay(9));
    }

    @Test
    void ignoresSkillsWithoutRepoUrl() {
        var backoff = new SkillRepoSyncBackoff();

        backoff.failed(null);
        backoff.succeeded(null);

        assertFalse(backoff.deferred(null));
    }
}
