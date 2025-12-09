package ai.core.memory.decay;

import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.MemoryType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ExponentialDecayPolicy.
 *
 * @author xander
 */
class ExponentialDecayPolicyTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExponentialDecayPolicyTest.class);

    @Test
    void testDefaultConstructor() {
        var policy = new ExponentialDecayPolicy();

        // Just verify it doesn't throw
        var entry = createEntry(1.0, 0.5, 0, Instant.now());
        double strength = policy.calculateStrength(entry, Instant.now());

        assertTrue(strength >= 0 && strength <= 1);

        LOGGER.info("default constructor test passed");
    }

    @Test
    void testCustomParameters() {
        var policy = new ExponentialDecayPolicy(0.05, Duration.ofDays(2));

        var entry = createEntry(1.0, 0.5, 0, Instant.now());
        double strength = policy.calculateStrength(entry, Instant.now());

        assertTrue(strength >= 0 && strength <= 1);

        LOGGER.info("custom parameters test passed");
    }

    @Test
    void testNoDecayForRecentMemory() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var entry = createEntry(1.0, 0.5, 5, now);

        double strength = policy.calculateStrength(entry, now);

        // Recent memory with high access count should have high strength
        assertTrue(strength > 0.8);

        LOGGER.info("no decay for recent memory test passed, strength={}", strength);
    }

    @Test
    void testDecayOverTime() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);
        var entry = createEntry(1.0, 0.5, 0, thirtyDaysAgo);

        double strength = policy.calculateStrength(entry, now);

        // Old memory without access should decay
        assertTrue(strength < 0.5);

        LOGGER.info("decay over time test passed, strength={}", strength);
    }

    @Test
    void testFrequencyBoost() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var weekAgo = now.minus(7, ChronoUnit.DAYS);

        var lowAccessEntry = createEntry(1.0, 0.5, 1, weekAgo);
        var highAccessEntry = createEntry(1.0, 0.5, 10, weekAgo);

        double lowStrength = policy.calculateStrength(lowAccessEntry, now);
        double highStrength = policy.calculateStrength(highAccessEntry, now);

        // Higher access count should result in higher strength
        assertTrue(highStrength > lowStrength);

        LOGGER.info("frequency boost test passed, low={}, high={}", lowStrength, highStrength);
    }

    @Test
    void testImportanceBoost() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var weekAgo = now.minus(7, ChronoUnit.DAYS);

        var lowImportance = createEntry(1.0, 0.2, 1, weekAgo);
        var highImportance = createEntry(1.0, 0.9, 1, weekAgo);

        double lowStrength = policy.calculateStrength(lowImportance, now);
        double highStrength = policy.calculateStrength(highImportance, now);

        // Higher importance should result in higher strength
        assertTrue(highStrength > lowStrength);

        LOGGER.info("importance boost test passed, low={}, high={}", lowStrength, highStrength);
    }

    @Test
    void testStrengthBoundedByOne() {
        var policy = new ExponentialDecayPolicy(0.01, Duration.ofDays(1));

        var now = Instant.now();
        // Very recent, high access, high importance
        var entry = createEntry(1.0, 1.0, 100, now);

        double strength = policy.calculateStrength(entry, now);

        assertTrue(strength <= 1.0);

        LOGGER.info("strength bounded by one test passed, strength={}", strength);
    }

    @Test
    void testVeryOldMemoryDecays() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var yearAgo = now.minus(365, ChronoUnit.DAYS);
        var entry = createEntry(1.0, 0.5, 0, yearAgo);

        double strength = policy.calculateStrength(entry, now);

        // Very old memory should decay significantly
        assertTrue(strength < 0.2);

        LOGGER.info("very old memory decays test passed, strength={}", strength);
    }

    @Test
    void testRecencyMatters() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var weekAgo = now.minus(7, ChronoUnit.DAYS);

        // Same creation time, different last access time
        var oldAccess = MemoryEntry.builder()
            .content("Old access")
            .type(MemoryType.SEMANTIC)
            .strength(1.0)
            .importance(0.5)
            .accessCount(5)
            .createdAt(weekAgo)
            .lastAccessedAt(weekAgo)
            .build();

        var recentAccess = MemoryEntry.builder()
            .content("Recent access")
            .type(MemoryType.SEMANTIC)
            .strength(1.0)
            .importance(0.5)
            .accessCount(5)
            .createdAt(weekAgo)
            .lastAccessedAt(now)
            .build();

        double oldStrength = policy.calculateStrength(oldAccess, now);
        double recentStrength = policy.calculateStrength(recentAccess, now);

        // More recently accessed should have higher strength
        assertTrue(recentStrength > oldStrength);

        LOGGER.info("recency matters test passed, old={}, recent={}", oldStrength, recentStrength);
    }

    @Test
    void testZeroAccessCount() {
        var policy = new ExponentialDecayPolicy(0.1, Duration.ofDays(1));

        var now = Instant.now();
        var entry = createEntry(1.0, 0.5, 0, now.minus(1, ChronoUnit.DAYS));

        double strength = policy.calculateStrength(entry, now);

        // Should not throw and should be valid
        assertTrue(strength >= 0 && strength <= 1);

        LOGGER.info("zero access count test passed, strength={}", strength);
    }

    @Test
    void testGetters() {
        var policy = new ExponentialDecayPolicy(0.2, Duration.ofDays(7));

        assertEquals(0.2, policy.getDecayRate());
        assertEquals(Duration.ofDays(7), policy.getDecayInterval());

        LOGGER.info("getters test passed");
    }

    private MemoryEntry createEntry(double strength, double importance, int accessCount, Instant lastAccessed) {
        return MemoryEntry.builder()
            .content("Test memory")
            .type(MemoryType.SEMANTIC)
            .strength(strength)
            .importance(importance)
            .accessCount(accessCount)
            .createdAt(lastAccessed)
            .lastAccessedAt(lastAccessed)
            .build();
    }
}
