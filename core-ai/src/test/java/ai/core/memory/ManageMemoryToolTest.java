package ai.core.memory;

import ai.core.memory.store.InMemoryStore;
import ai.core.memory.tool.ManageMemoryTool;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ManageMemoryTool.
 *
 * @author xander
 */
class ManageMemoryToolTest {

    private MemoryManager memoryManager;
    private ManageMemoryTool tool;

    @BeforeEach
    void setUp() {
        var store = new InMemoryStore();
        memoryManager = new MemoryManager(store, null, null);
        tool = ManageMemoryTool.builder()
            .memoryManager(memoryManager)
            .userId("user-1")
            .build();
    }

    @Test
    void testBuilderDefaults() {
        assertEquals("manage_memory", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("add"));
        assertTrue(tool.getDescription().contains("update"));
        assertTrue(tool.getDescription().contains("delete"));
    }

    @Test
    void testBuilderNullMemoryManager() {
        assertThrows(IllegalArgumentException.class, () ->
            ManageMemoryTool.builder()
                .userId("user-1")
                .build()
        );
    }

    @Test
    void testAddAction() {
        ToolCallResult result = tool.execute("{\"action\": \"add\", \"content\": \"User likes coffee\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("Memory added"));
        assertTrue(result.getResult().contains("ID:"));

        var memories = memoryManager.getMemories("user-1", 10);
        assertEquals(1, memories.size());
        assertEquals("User likes coffee", memories.getFirst().getContent());
    }

    @Test
    void testAddActionMissingContent() {
        ToolCallResult result = tool.execute("{\"action\": \"add\"}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("content"));
    }

    @Test
    void testUpdateAction() {
        // First add a memory
        memoryManager.addMemory("user-1", "Original content");
        var memories = memoryManager.getMemories("user-1", 10);
        String memoryId = memories.getFirst().getId();

        // Update it
        ToolCallResult result = tool.execute(
            "{\"action\": \"update\", \"memoryId\": \"" + memoryId + "\", \"content\": \"Updated content\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("updated"));

        var updated = memoryManager.getMemoryById(memoryId);
        assertEquals("Updated content", updated.getContent());
    }

    @Test
    void testUpdateActionNotFound() {
        ToolCallResult result = tool.execute(
            "{\"action\": \"update\", \"memoryId\": \"nonexistent\", \"content\": \"New content\"}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("not found"));
    }

    @Test
    void testDeleteAction() {
        memoryManager.addMemory("user-1", "To be deleted");
        var memories = memoryManager.getMemories("user-1", 10);
        String memoryId = memories.getFirst().getId();

        ToolCallResult result = tool.execute("{\"action\": \"delete\", \"memoryId\": \"" + memoryId + "\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("deleted"));

        var remaining = memoryManager.getMemories("user-1", 10);
        assertTrue(remaining.isEmpty());
    }

    @Test
    void testDeleteActionNotFound() {
        ToolCallResult result = tool.execute("{\"action\": \"delete\", \"memoryId\": \"nonexistent\"}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("not found"));
    }

    @Test
    void testListAction() {
        memoryManager.addMemory("user-1", "Memory 1");
        memoryManager.addMemory("user-1", "Memory 2");

        ToolCallResult result = tool.execute("{\"action\": \"list\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("2 memories"));
        assertTrue(result.getResult().contains("Memory 1"));
        assertTrue(result.getResult().contains("Memory 2"));
    }

    @Test
    void testListActionEmpty() {
        ToolCallResult result = tool.execute("{\"action\": \"list\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("No memories"));
    }

    @Test
    void testListActionWithLimit() {
        for (int i = 0; i < 10; i++) {
            memoryManager.addMemory("user-1", "Memory " + i);
        }

        ToolCallResult result = tool.execute("{\"action\": \"list\", \"limit\": 3}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("3 memories"));
    }

    @Test
    void testGetAction() {
        memoryManager.addMemory("user-1", "Test memory");
        var memories = memoryManager.getMemories("user-1", 10);
        String memoryId = memories.getFirst().getId();

        ToolCallResult result = tool.execute("{\"action\": \"get\", \"memoryId\": \"" + memoryId + "\"}");

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("Test memory"));
        assertTrue(result.getResult().contains(memoryId));
    }

    @Test
    void testGetActionNotFound() {
        ToolCallResult result = tool.execute("{\"action\": \"get\", \"memoryId\": \"nonexistent\"}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("not found"));
    }

    @Test
    void testUnknownAction() {
        ToolCallResult result = tool.execute("{\"action\": \"unknown\"}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("Unknown action"));
    }

    @Test
    void testMissingAction() {
        ToolCallResult result = tool.execute("{}");

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("action"));
    }

    @Test
    void testInvalidJson() {
        ToolCallResult result = tool.execute("invalid json");

        assertTrue(result.isFailed());
    }

    @Test
    void testGetParameters() {
        var params = tool.getParameters();

        assertEquals(4, params.size());
        assertEquals("action", params.get(0).getName());
        assertEquals("content", params.get(1).getName());
        assertEquals("memoryId", params.get(2).getName());
        assertEquals("limit", params.get(3).getName());
    }
}
