package ai.core.memory;

import ai.core.memory.store.InMemoryStore;
import ai.core.memory.tool.SearchMemoryTool;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SearchMemoryTool.
 *
 * @author xander
 */
class SearchMemoryToolTest {

    private MemoryManager memoryManager;
    private SearchMemoryTool tool;

    @BeforeEach
    void setUp() {
        var store = new InMemoryStore();
        memoryManager = new MemoryManager(store, null, null);
        tool = SearchMemoryTool.builder()
            .memoryManager(memoryManager)
            .userId("user-1")
            .build();
    }

    @Test
    void testBuilderDefaults() {
        assertEquals("search_memory", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("memory"));
    }

    @Test
    void testBuilderCustomName() {
        var customTool = SearchMemoryTool.builder()
            .memoryManager(memoryManager)
            .userId("user-1")
            .name("custom_memory_search")
            .description("Custom description")
            .build();

        assertEquals("custom_memory_search", customTool.getName());
        assertEquals("Custom description", customTool.getDescription());
    }

    @Test
    void testBuilderNullMemoryManager() {
        assertThrows(IllegalArgumentException.class, () ->
            SearchMemoryTool.builder()
                .userId("user-1")
                .build()
        );
    }

    @Test
    void testSearchWithResults() {
        memoryManager.addMemory("user-1", "User likes coffee");
        memoryManager.addMemory("user-1", "User prefers dark mode");

        ToolCallResult result = tool.execute("{\"query\": \"coffee\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("coffee"));
    }

    @Test
    void testSearchNoResults() {
        ToolCallResult result = tool.execute("{\"query\": \"nonexistent\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("No relevant memories"));
    }

    @Test
    void testSearchEmptyQuery() {
        ToolCallResult result = tool.execute("{\"query\": \"\"}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("query"));
    }

    @Test
    void testSearchMissingQuery() {
        ToolCallResult result = tool.execute("{}");

        assertTrue(result.isFailed());
    }

    @Test
    void testSearchWithTopK() {
        for (int i = 0; i < 10; i++) {
            memoryManager.addMemory("user-1", "Memory " + i);
        }

        ToolCallResult result = tool.execute("{\"query\": \"Memory\", \"topK\": 3}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("3 relevant memories"));
    }

    @Test
    void testSearchInvalidJson() {
        ToolCallResult result = tool.execute("invalid json");

        assertTrue(result.isFailed());
    }

    @Test
    void testGetParameters() {
        var params = tool.getParameters();

        assertEquals(2, params.size());
        assertEquals("query", params.get(0).getName());
        assertEquals("topK", params.get(1).getName());
    }
}
