package ai.core.memory;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.store.InMemoryStore;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Simple memory manager for long-term memory operations.
 * Extracts memories from conversations and stores them for later retrieval.
 *
 * @author xander
 */
public class MemoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryManager.class);
    private static final double DEFAULT_TEMPERATURE = 0.3;

    private static final String EXTRACTION_PROMPT_TEMPLATE = """
        Analyze the conversation and extract key facts about the user that should be remembered long-term.

        Conversation:
        {{CONVERSATION}}

        Extract important information such as:
        - User preferences (likes, dislikes, habits)
        - Personal facts (name, location, job, interests)
        - Important events or experiences mentioned

        Rules:
        - IGNORE trivial information, greetings, temporary context
        - Focus on LONG-TERM relevant information only
        - Be CONCISE - each memory should be a single sentence
        - Return empty array if nothing important to remember

        Output JSON only:
        {"memories": ["memory 1", "memory 2", ...]}
        """;

    private final LongTermMemory store;
    private final LLMProvider llmProvider;
    private final String model;
    private final Executor executor;
    private final boolean enabled;
    private final double temperature;

    /**
     * Create a MemoryManager with default in-memory store.
     */
    public static MemoryManager create(LLMProvider llmProvider, String model) {
        return new MemoryManager(new InMemoryStore(llmProvider), llmProvider, model);
    }

    /**
     * Create a MemoryManager with custom store.
     */
    public static MemoryManager create(LongTermMemory store, LLMProvider llmProvider, String model) {
        return new MemoryManager(store, llmProvider, model);
    }

    public MemoryManager(LongTermMemory store, LLMProvider llmProvider, String model) {
        this(store, llmProvider, model, ForkJoinPool.commonPool(), true, DEFAULT_TEMPERATURE);
    }

    public MemoryManager(LongTermMemory store, LLMProvider llmProvider, String model, Executor executor, boolean enabled) {
        this(store, llmProvider, model, executor, enabled, DEFAULT_TEMPERATURE);
    }

    public MemoryManager(LongTermMemory store, LLMProvider llmProvider, String model,
                         Executor executor, boolean enabled, double temperature) {
        this.store = store;
        this.llmProvider = llmProvider;
        this.model = model;
        this.executor = executor;
        this.enabled = enabled;
        this.temperature = temperature;
    }

    /**
     * Extract and store memories from a conversation.
     *
     * @param messages the conversation messages
     * @param userId   the user identifier
     */
    public void processConversation(List<Message> messages, String userId) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return;
        }

        LOGGER.debug("Processing conversation for memory extraction, userId={}", userId);

        List<String> memories = extractMemories(messages);
        if (memories.isEmpty()) {
            LOGGER.debug("No memories extracted from conversation");
            return;
        }

        for (String content : memories) {
            store.add(MemoryEntry.of(userId, content));
        }

        LOGGER.info("Extracted and stored {} memories for user {}", memories.size(), userId);
    }

    /**
     * Process conversation asynchronously.
     */
    public CompletableFuture<Void> processConversationAsync(List<Message> messages, String userId) {
        return CompletableFuture.runAsync(() -> processConversation(messages, userId), executor);
    }

    /**
     * Search memories relevant to a query.
     *
     * @param query  the search query
     * @param userId the user ID
     * @param topK   maximum results
     * @return list of relevant memories
     */
    public List<MemoryEntry> search(String query, String userId, int topK) {
        return store.search(query, userId, topK);
    }

    /**
     * Get all memories for a user.
     */
    public List<MemoryEntry> getMemories(String userId, int limit) {
        return store.getByUserId(userId, limit);
    }

    /**
     * Add a memory directly.
     */
    public void addMemory(String userId, String content) {
        store.add(userId, content);
    }

    /**
     * Add a memory entry directly.
     */
    public void addMemory(MemoryEntry entry) {
        store.add(entry);
    }

    /**
     * Delete a memory by ID.
     */
    public void deleteMemory(String memoryId) {
        store.delete(memoryId);
    }

    /**
     * Build context string for prompt injection.
     */
    public String buildContext(String userId, int limit) {
        return store.buildContext(userId, limit);
    }

    /**
     * Get the underlying store.
     */
    public LongTermMemory getStore() {
        return store;
    }

    /**
     * Check if memory extraction is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    private List<String> extractMemories(List<Message> messages) {
        if (llmProvider == null) {
            return List.of();
        }

        String conversation = formatConversation(messages);
        String prompt = EXTRACTION_PROMPT_TEMPLATE.replace("{{CONVERSATION}}", conversation);

        try {
            var request = CompletionRequest.of(
                List.of(Message.of(RoleType.USER, prompt)),
                null, temperature, model, "memory-extractor"
            );

            var response = llmProvider.completion(request);

            if (response == null || response.choices == null || response.choices.isEmpty()) {
                return List.of();
            }

            String content = response.choices.getFirst().message.content;
            return parseMemories(content);
        } catch (Exception e) {
            LOGGER.warn("Failed to extract memories: {}", e.getMessage());
            return List.of();
        }
    }

    private String formatConversation(List<Message> messages) {
        var sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.role == RoleType.SYSTEM) continue;
            String role = switch (msg.role) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                default -> "Other";
            };
            sb.append(role).append(": ").append(msg.content != null ? msg.content : "").append('\n');
        }
        return sb.toString();
    }

    private List<String> parseMemories(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return List.of();
        }

        // Clean up JSON if wrapped in markdown code blocks
        String cleaned = jsonContent.trim();
        if (cleaned.startsWith("```")) {
            int start = cleaned.indexOf('\n') + 1;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        }

        try {
            var result = JSON.fromJSON(ExtractionResult.class, cleaned);
            return result.memories();
        } catch (Exception e) {
            LOGGER.debug("Failed to parse extraction result: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * DTO for extraction result.
     */
    public record ExtractionResult(List<String> memories) {
        public ExtractionResult {
            memories = memories != null ? memories : List.of();
        }
    }
}
