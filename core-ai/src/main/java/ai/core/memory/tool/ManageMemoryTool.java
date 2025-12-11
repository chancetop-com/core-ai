package ai.core.memory.tool;

import ai.core.memory.MemoryManager;
import ai.core.memory.model.MemoryEntry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Tool for agents to manage long-term memory (CRUD operations).
 * Allows agents to add, update, delete, and list memories.
 *
 * @author xander
 */
public class ManageMemoryTool extends ToolCall {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIST_LIMIT = 10;

    public static Builder builder() {
        return new Builder();
    }

    private final MemoryManager memoryManager;
    private final String userId;

    public ManageMemoryTool(MemoryManager memoryManager, String userId) {
        if (memoryManager == null) {
            throw new IllegalArgumentException("memoryManager cannot be null");
        }
        this.memoryManager = memoryManager;
        this.userId = userId;
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            JsonNode params = MAPPER.readTree(text);

            String action = params.has("action") ? params.get("action").asText() : "";
            if (action.isEmpty()) {
                return ToolCallResult.failed("Error: 'action' parameter is required (add/update/delete/list/get)")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            return switch (action.toLowerCase(java.util.Locale.ROOT)) {
                case "add" -> handleAdd(params, startTime);
                case "update" -> handleUpdate(params, startTime);
                case "delete" -> handleDelete(params, startTime);
                case "list" -> handleList(params, startTime);
                case "get" -> handleGet(params, startTime);
                default -> ToolCallResult.failed("Error: Unknown action '" + action + "'. Use: add/update/delete/list/get")
                    .withDuration(System.currentTimeMillis() - startTime);
            };

        } catch (Exception e) {
            return ToolCallResult.failed("Error managing memory: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult handleAdd(JsonNode params, long startTime) {
        String content = params.has("content") ? params.get("content").asText() : "";
        if (content.isEmpty()) {
            return ToolCallResult.failed("Error: 'content' is required for add action")
                .withDuration(System.currentTimeMillis() - startTime);
        }

        var entry = MemoryEntry.of(userId, content);
        memoryManager.addMemory(entry);

        return ToolCallResult.completed("Memory added successfully. ID: " + entry.getId())
            .withDuration(System.currentTimeMillis() - startTime)
            .withStats("action", "add")
            .withStats("memoryId", entry.getId());
    }

    private ToolCallResult handleUpdate(JsonNode params, long startTime) {
        String memoryId = params.has("memoryId") ? params.get("memoryId").asText() : "";
        String content = params.has("content") ? params.get("content").asText() : "";

        if (memoryId.isEmpty()) {
            return ToolCallResult.failed("Error: 'memoryId' is required for update action")
                .withDuration(System.currentTimeMillis() - startTime);
        }
        if (content.isEmpty()) {
            return ToolCallResult.failed("Error: 'content' is required for update action")
                .withDuration(System.currentTimeMillis() - startTime);
        }

        var existing = memoryManager.getMemoryById(memoryId);
        if (existing == null) {
            return ToolCallResult.failed("Error: Memory not found with ID: " + memoryId)
                .withDuration(System.currentTimeMillis() - startTime);
        }

        memoryManager.updateMemory(memoryId, content);

        return ToolCallResult.completed("Memory updated successfully. ID: " + memoryId)
            .withDuration(System.currentTimeMillis() - startTime)
            .withStats("action", "update")
            .withStats("memoryId", memoryId);
    }

    private ToolCallResult handleDelete(JsonNode params, long startTime) {
        String memoryId = params.has("memoryId") ? params.get("memoryId").asText() : "";

        if (memoryId.isEmpty()) {
            return ToolCallResult.failed("Error: 'memoryId' is required for delete action")
                .withDuration(System.currentTimeMillis() - startTime);
        }

        var existing = memoryManager.getMemoryById(memoryId);
        if (existing == null) {
            return ToolCallResult.failed("Error: Memory not found with ID: " + memoryId)
                .withDuration(System.currentTimeMillis() - startTime);
        }

        memoryManager.deleteMemory(memoryId);

        return ToolCallResult.completed("Memory deleted successfully. ID: " + memoryId)
            .withDuration(System.currentTimeMillis() - startTime)
            .withStats("action", "delete")
            .withStats("memoryId", memoryId);
    }

    private ToolCallResult handleList(JsonNode params, long startTime) {
        int limit = params.has("limit") ? params.get("limit").asInt() : DEFAULT_LIST_LIMIT;

        List<MemoryEntry> memories = memoryManager.getMemories(userId, limit);

        if (memories.isEmpty()) {
            return ToolCallResult.completed("No memories found for user.")
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("action", "list")
                .withStats("count", 0);
        }

        var sb = new StringBuilder(256);
        sb.append("Found ").append(memories.size()).append(" memories:\n");
        for (var memory : memories) {
            sb.append("- [").append(memory.getId()).append("] ").append(memory.getContent()).append('\n');
        }

        return ToolCallResult.completed(sb.toString())
            .withDuration(System.currentTimeMillis() - startTime)
            .withStats("action", "list")
            .withStats("count", memories.size());
    }

    private ToolCallResult handleGet(JsonNode params, long startTime) {
        String memoryId = params.has("memoryId") ? params.get("memoryId").asText() : "";

        if (memoryId.isEmpty()) {
            return ToolCallResult.failed("Error: 'memoryId' is required for get action")
                .withDuration(System.currentTimeMillis() - startTime);
        }

        var memory = memoryManager.getMemoryById(memoryId);
        if (memory == null) {
            return ToolCallResult.failed("Error: Memory not found with ID: " + memoryId)
                .withDuration(System.currentTimeMillis() - startTime);
        }

        var result = String.format("Memory [%s]:\nContent: %s\nCreated: %s",
            memory.getId(), memory.getContent(), memory.getCreatedAt());

        return ToolCallResult.completed(result)
            .withDuration(System.currentTimeMillis() - startTime)
            .withStats("action", "get")
            .withStats("memoryId", memoryId);
    }

    @Override
    public List<ToolCallParameter> getParameters() {
        return List.of(
            ToolCallParameter.builder()
                .name("action")
                .description("The action to perform: 'add', 'update', 'delete', 'list', or 'get'")
                .type(ToolCallParameterType.STRING)
                .classType(String.class)
                .required(true)
                .build(),
            ToolCallParameter.builder()
                .name("content")
                .description("The memory content (required for 'add' and 'update' actions)")
                .type(ToolCallParameterType.STRING)
                .classType(String.class)
                .required(false)
                .build(),
            ToolCallParameter.builder()
                .name("memoryId")
                .description("The memory ID (required for 'update', 'delete', and 'get' actions)")
                .type(ToolCallParameterType.STRING)
                .classType(String.class)
                .required(false)
                .build(),
            ToolCallParameter.builder()
                .name("limit")
                .description("Maximum number of memories to return for 'list' action (default: 10)")
                .type(ToolCallParameterType.INTEGER)
                .classType(Integer.class)
                .required(false)
                .build()
        );
    }

    public static class Builder extends ToolCall.Builder<Builder, ManageMemoryTool> {
        private MemoryManager memoryManager;
        private String userId;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder memoryManager(MemoryManager memoryManager) {
            this.memoryManager = memoryManager;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public ManageMemoryTool build() {
            var tool = new ManageMemoryTool(memoryManager, userId);

            try {
                super.build(tool);
            } catch (RuntimeException e) {
                if (e.getMessage() != null
                    && (e.getMessage().contains("name is required")
                        || e.getMessage().contains("description is required"))) {
                    name("manage_memory");
                    description("Manage user's long-term memory. Actions: "
                        + "'add' (store new memory), "
                        + "'update' (modify existing), "
                        + "'delete' (remove memory), "
                        + "'list' (show all memories), "
                        + "'get' (retrieve by ID)");
                    super.build(tool);
                } else {
                    throw e;
                }
            }

            return tool;
        }
    }
}
