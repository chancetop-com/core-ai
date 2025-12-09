package ai.core.memory.model;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryContext.
 *
 * @author xander
 */
class MemoryContextTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryContextTest.class);

    @Test
    void testEmpty() {
        var context = MemoryContext.empty();

        assertTrue(context.isEmpty());
        assertEquals(0, context.size());
        assertTrue(context.getSemanticMemories().isEmpty());
        assertTrue(context.getEpisodicMemories().isEmpty());

        LOGGER.info("empty test passed");
    }

    @Test
    void testDefaultConstructor() {
        var context = new MemoryContext();

        assertTrue(context.isEmpty());
        assertNotNull(context.getSemanticMemories());
        assertNotNull(context.getEpisodicMemories());

        LOGGER.info("default constructor test passed");
    }

    @Test
    void testConstructorWithLists() {
        var semantic = List.of(
            MemoryEntry.builder().content("Fact 1").type(MemoryType.SEMANTIC).build(),
            MemoryEntry.builder().content("Fact 2").type(MemoryType.SEMANTIC).build()
        );
        var episodic = List.of(
            MemoryEntry.builder().content("Event 1").type(MemoryType.EPISODIC).build()
        );

        var context = new MemoryContext(semantic, episodic);

        assertFalse(context.isEmpty());
        assertEquals(3, context.size());
        assertEquals(2, context.getSemanticMemories().size());
        assertEquals(1, context.getEpisodicMemories().size());

        LOGGER.info("constructor with lists test passed");
    }

    @Test
    void testConstructorWithNullLists() {
        var context = new MemoryContext(null, null);

        assertTrue(context.isEmpty());
        assertNotNull(context.getSemanticMemories());
        assertNotNull(context.getEpisodicMemories());

        LOGGER.info("constructor with null lists test passed");
    }

    @Test
    void testFromMemories() {
        var memories = List.of(
            MemoryEntry.builder().content("Semantic 1").type(MemoryType.SEMANTIC).build(),
            MemoryEntry.builder().content("Semantic 2").type(MemoryType.SEMANTIC).build(),
            MemoryEntry.builder().content("Episodic 1").type(MemoryType.EPISODIC).build()
        );

        var context = MemoryContext.fromMemories(memories);

        assertEquals(3, context.size());
        assertEquals(2, context.getSemanticMemories().size());
        assertEquals(1, context.getEpisodicMemories().size());

        LOGGER.info("fromMemories test passed");
    }

    @Test
    void testGetAllMemories() {
        var semantic = List.of(
            MemoryEntry.builder().content("S1").type(MemoryType.SEMANTIC).build()
        );
        var episodic = List.of(
            MemoryEntry.builder().content("E1").type(MemoryType.EPISODIC).build()
        );

        var context = new MemoryContext(semantic, episodic);
        var all = context.getAllMemories();

        assertEquals(2, all.size());

        LOGGER.info("getAllMemories test passed");
    }

    @Test
    void testBuildContextStringEmpty() {
        var context = MemoryContext.empty();

        assertEquals("", context.buildContextString());

        LOGGER.info("buildContextString empty test passed");
    }

    @Test
    void testBuildContextStringSemanticOnly() {
        var semantic = List.of(
            MemoryEntry.builder().content("User likes coffee").type(MemoryType.SEMANTIC).build(),
            MemoryEntry.builder().content("User prefers dark mode").type(MemoryType.SEMANTIC).build()
        );

        var context = new MemoryContext(semantic, List.of());
        var result = context.buildContextString();

        assertTrue(result.contains("[User Memory]"));
        assertTrue(result.contains("User likes coffee"));
        assertTrue(result.contains("User prefers dark mode"));
        assertFalse(result.contains("[Relevant Experiences]"));

        LOGGER.info("buildContextString semantic only test passed: {}", result);
    }

    @Test
    void testBuildContextStringEpisodicOnly() {
        var episodic = List.of(
            MemoryEntry.builder().content("Past event").type(MemoryType.EPISODIC).build()
        );

        var context = new MemoryContext(List.of(), episodic);
        var result = context.buildContextString();

        assertFalse(result.contains("[User Memory]"));
        assertTrue(result.contains("[Relevant Experiences]"));
        assertTrue(result.contains("Past event"));

        LOGGER.info("buildContextString episodic only test passed: {}", result);
    }

    @Test
    void testBuildContextStringWithEpisodicMemoryEntry() {
        List<MemoryEntry> episodic = List.of(
            EpisodicMemoryEntry.builder()
                .content("Event content")
                .situation("User asked about weather")
                .action("Provided forecast")
                .outcome("User satisfied")
                .build()
        );

        var context = new MemoryContext(List.of(), episodic);
        var result = context.buildContextString();

        assertTrue(result.contains("Situation: User asked about weather"));
        assertTrue(result.contains("Action: Provided forecast"));
        assertTrue(result.contains("Outcome: User satisfied"));

        LOGGER.info("buildContextString with EpisodicMemoryEntry test passed: {}", result);
    }

    @Test
    void testBuildContextStringBoth() {
        var semantic = List.of(
            MemoryEntry.builder().content("User fact").type(MemoryType.SEMANTIC).build()
        );
        var episodic = List.of(
            MemoryEntry.builder().content("User event").type(MemoryType.EPISODIC).build()
        );

        var context = new MemoryContext(semantic, episodic);
        var result = context.buildContextString();

        assertTrue(result.contains("[User Memory]"));
        assertTrue(result.contains("[Relevant Experiences]"));
        assertTrue(result.contains("User fact"));
        assertTrue(result.contains("User event"));

        LOGGER.info("buildContextString both test passed");
    }

    @Test
    void testBuildSummaryEmpty() {
        var context = MemoryContext.empty();

        assertEquals("", context.buildSummary());

        LOGGER.info("buildSummary empty test passed");
    }

    @Test
    void testBuildSummary() {
        var memories = List.of(
            MemoryEntry.builder().content("Fact 1").type(MemoryType.SEMANTIC).build(),
            MemoryEntry.builder().content("Fact 2").type(MemoryType.SEMANTIC).build(),
            MemoryEntry.builder().content("Event 1").type(MemoryType.EPISODIC).build()
        );

        var context = MemoryContext.fromMemories(memories);
        var summary = context.buildSummary();

        assertTrue(summary.contains("Fact 1"));
        assertTrue(summary.contains("Fact 2"));
        assertTrue(summary.contains("Event 1"));
        assertTrue(summary.contains("; "));

        LOGGER.info("buildSummary test passed: {}", summary);
    }
}
