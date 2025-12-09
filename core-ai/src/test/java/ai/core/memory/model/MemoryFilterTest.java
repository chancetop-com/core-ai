package ai.core.memory.model;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryFilter.
 *
 * @author xander
 */
class MemoryFilterTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryFilterTest.class);

    @Test
    void testForUser() {
        var filter = MemoryFilter.forUser("user-123");

        assertEquals("user-123", filter.getUserId());
        assertNull(filter.getAgentId());
        assertNull(filter.getTypes());

        LOGGER.info("forUser test passed");
    }

    @Test
    void testEmpty() {
        var filter = MemoryFilter.empty();

        assertNull(filter.getUserId());
        assertNull(filter.getAgentId());
        assertNull(filter.getTypes());

        LOGGER.info("empty test passed");
    }

    @Test
    void testChainMethods() {
        var filter = MemoryFilter.forUser("user-1")
            .withAgentId("agent-1")
            .withTypes(MemoryType.SEMANTIC, MemoryType.EPISODIC)
            .withCategories(SemanticCategory.FACT, SemanticCategory.PREFERENCE)
            .withMinImportance(0.5)
            .withMinStrength(0.3)
            .withSimilarityThreshold(0.7);

        assertEquals("user-1", filter.getUserId());
        assertEquals("agent-1", filter.getAgentId());
        assertEquals(2, filter.getTypes().size());
        assertTrue(filter.getTypes().contains(MemoryType.SEMANTIC));
        assertTrue(filter.getTypes().contains(MemoryType.EPISODIC));
        assertEquals(2, filter.getCategories().size());
        assertEquals(0.5, filter.getMinImportance());
        assertEquals(0.3, filter.getMinStrength());
        assertEquals(0.7, filter.getSimilarityThreshold());

        LOGGER.info("chain methods test passed");
    }

    @Test
    void testTimeFilters() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS);

        var filter = MemoryFilter.empty()
            .after(yesterday)
            .before(tomorrow);

        assertEquals(yesterday, filter.getAfter());
        assertEquals(tomorrow, filter.getBefore());

        LOGGER.info("time filters test passed");
    }

    @Test
    void testMatchesUserId() {
        var filter = MemoryFilter.forUser("user-1");

        var matchingEntry = MemoryEntry.builder()
            .userId("user-1")
            .content("Test")
            .build();

        var nonMatchingEntry = MemoryEntry.builder()
            .userId("user-2")
            .content("Test")
            .build();

        assertTrue(filter.matches(matchingEntry));
        assertFalse(filter.matches(nonMatchingEntry));

        LOGGER.info("matches userId test passed");
    }

    @Test
    void testMatchesAgentId() {
        var filter = MemoryFilter.empty().withAgentId("agent-1");

        var matchingEntry = MemoryEntry.builder()
            .agentId("agent-1")
            .content("Test")
            .build();

        var nonMatchingEntry = MemoryEntry.builder()
            .agentId("agent-2")
            .content("Test")
            .build();

        assertTrue(filter.matches(matchingEntry));
        assertFalse(filter.matches(nonMatchingEntry));

        LOGGER.info("matches agentId test passed");
    }

    @Test
    void testMatchesTypes() {
        var filter = MemoryFilter.empty().withTypes(MemoryType.SEMANTIC);

        var semanticEntry = MemoryEntry.builder()
            .content("Test")
            .type(MemoryType.SEMANTIC)
            .build();

        var episodicEntry = MemoryEntry.builder()
            .content("Test")
            .type(MemoryType.EPISODIC)
            .build();

        assertTrue(filter.matches(semanticEntry));
        assertFalse(filter.matches(episodicEntry));

        LOGGER.info("matches types test passed");
    }

    @Test
    void testMatchesCategories() {
        var filter = MemoryFilter.empty()
            .withCategories(SemanticCategory.PREFERENCE);

        var preferenceEntry = SemanticMemoryEntry.builder()
            .content("Test")
            .category(SemanticCategory.PREFERENCE)
            .build();

        var factEntry = SemanticMemoryEntry.builder()
            .content("Test")
            .category(SemanticCategory.FACT)
            .build();

        var nonSemanticEntry = EpisodicMemoryEntry.builder()
            .content("Test")
            .build();

        assertTrue(filter.matches(preferenceEntry));
        assertFalse(filter.matches(factEntry));
        assertFalse(filter.matches(nonSemanticEntry));

        LOGGER.info("matches categories test passed");
    }

    @Test
    void testMatchesMinImportance() {
        var filter = MemoryFilter.empty().withMinImportance(0.5);

        var highImportance = MemoryEntry.builder()
            .content("Test")
            .importance(0.8)
            .build();

        var lowImportance = MemoryEntry.builder()
            .content("Test")
            .importance(0.3)
            .build();

        assertTrue(filter.matches(highImportance));
        assertFalse(filter.matches(lowImportance));

        LOGGER.info("matches minImportance test passed");
    }

    @Test
    void testMatchesMinStrength() {
        var filter = MemoryFilter.empty().withMinStrength(0.5);

        var strongEntry = MemoryEntry.builder()
            .content("Test")
            .strength(0.8)
            .build();

        var weakEntry = MemoryEntry.builder()
            .content("Test")
            .strength(0.3)
            .build();

        assertTrue(filter.matches(strongEntry));
        assertFalse(filter.matches(weakEntry));

        LOGGER.info("matches minStrength test passed");
    }

    @Test
    void testMatchesTimeRange() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        Instant twoDaysAgo = now.minus(2, ChronoUnit.DAYS);
        Instant tomorrow = now.plus(1, ChronoUnit.DAYS);

        var filter = MemoryFilter.empty()
            .after(yesterday)
            .before(tomorrow);

        var recentEntry = MemoryEntry.builder()
            .content("Test")
            .createdAt(now)
            .build();

        var oldEntry = MemoryEntry.builder()
            .content("Test")
            .createdAt(twoDaysAgo)
            .build();

        assertTrue(filter.matches(recentEntry));
        assertFalse(filter.matches(oldEntry));

        LOGGER.info("matches time range test passed");
    }

    @Test
    void testMatchesEmptyFilter() {
        var filter = MemoryFilter.empty();

        var entry = MemoryEntry.builder()
            .userId("any-user")
            .agentId("any-agent")
            .content("Test")
            .type(MemoryType.SEMANTIC)
            .importance(0.1)
            .strength(0.1)
            .build();

        assertTrue(filter.matches(entry));

        LOGGER.info("matches empty filter test passed");
    }

    @Test
    void testMatchesCombinedFilters() {
        var filter = MemoryFilter.forUser("user-1")
            .withTypes(MemoryType.SEMANTIC)
            .withMinImportance(0.5)
            .withMinStrength(0.3);

        var matchingEntry = MemoryEntry.builder()
            .userId("user-1")
            .content("Test")
            .type(MemoryType.SEMANTIC)
            .importance(0.7)
            .strength(0.5)
            .build();

        var wrongUser = MemoryEntry.builder()
            .userId("user-2")
            .content("Test")
            .type(MemoryType.SEMANTIC)
            .importance(0.7)
            .strength(0.5)
            .build();

        var wrongType = MemoryEntry.builder()
            .userId("user-1")
            .content("Test")
            .type(MemoryType.EPISODIC)
            .importance(0.7)
            .strength(0.5)
            .build();

        assertTrue(filter.matches(matchingEntry));
        assertFalse(filter.matches(wrongUser));
        assertFalse(filter.matches(wrongType));

        LOGGER.info("matches combined filters test passed");
    }

    @Test
    void testWithTypesList() {
        var types = List.of(MemoryType.SEMANTIC, MemoryType.EPISODIC);
        var filter = MemoryFilter.empty().withTypes(types);

        assertNotNull(filter.getTypes());
        assertEquals(2, filter.getTypes().size());

        LOGGER.info("withTypes list test passed");
    }
}
