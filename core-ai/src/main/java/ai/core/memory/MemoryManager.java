package ai.core.memory;

import ai.core.document.Document;
import ai.core.llm.domain.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages multiple memory layers and provides unified access to memory operations.
 * Coordinates saving and retrieval across all registered memory implementations.
 *
 * @author Xander
 */
public class MemoryManager {
    /**
     * Memory prompt template for system message formatting.
     */
    public static final String PROMPT_MEMORY_TEMPLATE = "\n\n### Memory\n";
    private static final String MEMORY_SECTION_TEMPLATE = "\n### %s:\n%s";

    private final List<Memory> memories = new ArrayList<>();

    public MemoryManager() {
    }

    public MemoryManager(List<Memory> memories) {
        if (memories != null) {
            this.memories.addAll(memories);
        }
    }

    /**
     * Register a memory layer.
     */
    public void addMemory(Memory memory) {
        if (memory != null) {
            memories.add(memory);
        }
    }

    /**
     * Save messages to all registered memory layers.
     */
    public void saveAll(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Memory memory : memories) {
            memory.save(messages);
        }
    }

    /**
     * Retrieve and format memory context from all layers for the given query.
     *
     * @param query the query to search memories
     * @return formatted memory context string, empty if no memories found
     */
    public String retrieveAsContext(String query) {
        StringBuilder context = new StringBuilder();
        for (Memory memory : memories) {
            List<Document> docs = memory.retrieve(query);
            if (docs != null && !docs.isEmpty()) {
                String docsContent = docs.stream()
                    .map(doc -> doc.content)
                    .collect(Collectors.joining("\n"));
                context.append(String.format(MEMORY_SECTION_TEMPLATE, memory.getType(), docsContent));
            }
        }
        return context.toString();
    }

    /**
     * Retrieve documents from all memory layers.
     */
    public List<Document> retrieveAll(String query) {
        List<Document> allDocs = new ArrayList<>();
        for (Memory memory : memories) {
            List<Document> docs = memory.retrieve(query);
            if (docs != null) {
                allDocs.addAll(docs);
            }
        }
        return allDocs;
    }

    /**
     * Clear all memory layers.
     */
    public void clearAll() {
        for (Memory memory : memories) {
            memory.clear();
        }
    }

    /**
     * Check if any memory layer has content.
     */
    public boolean hasMemory() {
        return memories.stream().anyMatch(m -> !m.isEmpty());
    }

    /**
     * Get the number of registered memory layers.
     */
    public int getMemoryLayerCount() {
        return memories.size();
    }

    /**
     * Get all registered memories (for advanced usage).
     */
    public List<Memory> getMemories() {
        return new ArrayList<>(memories);
    }
}
