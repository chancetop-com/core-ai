package ai.core.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.memory.history.ChatHistoryProvider;
import ai.core.memory.history.ChatRecord;
import ai.core.prompt.Prompts;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author xander
 */
public class Extraction {
    private static final Logger LOGGER = LoggerFactory.getLogger(Extraction.class);
    private static final int MAX_TURNS_PER_EXTRACTION = 50;
    private static final int MAX_TOKENS_PER_MESSAGE = 5000;

    public static Builder builder() {
        return new Builder();
    }

    private final MemoryStore memoryStore;
    private final ChatHistoryProvider historyProvider;
    private final LLMProvider llmProvider;
    private final String model;
    private final Map<String, Integer> extractedIndexMap = new ConcurrentHashMap<>();

    public Extraction(MemoryStore memoryStore, ChatHistoryProvider historyProvider, LLMProvider llmProvider) {
        this(builder()
            .memoryStore(memoryStore)
            .historyProvider(historyProvider)
            .llmProvider(llmProvider));
    }

    private Extraction(Builder builder) {
        this.memoryStore = builder.memoryStore;
        this.historyProvider = builder.historyProvider;
        this.llmProvider = builder.llmProvider;
        this.model = builder.model;
    }

    public void run(String userId) {
        List<ChatRecord> unextracted = loadUnextracted(userId);
        if (unextracted.isEmpty()) {
            LOGGER.debug("No unextracted messages for user: {}", userId);
            return;
        }

        int totalCount = historyProvider.loadForExtraction(userId).size();
        LOGGER.info("Triggering extraction: {} unextracted messages", unextracted.size());
        performExtraction(userId, unextracted, totalCount - 1);
    }

    private List<ChatRecord> loadUnextracted(String userId) {
        List<ChatRecord> all = historyProvider.loadForExtraction(userId);
        int lastExtracted = extractedIndexMap.getOrDefault(userId, -1);

        if (lastExtracted < 0) {
            return filterNonSystemRecords(all);
        }

        if (lastExtracted >= all.size() - 1) {
            return List.of();
        }

        return filterNonSystemRecords(all.subList(lastExtracted + 1, all.size()));
    }

    private List<ChatRecord> filterNonSystemRecords(List<ChatRecord> records) {
        return records.stream()
            .filter(r -> r.role() != RoleType.SYSTEM)
            .toList();
    }

    private void performExtraction(String userId, List<ChatRecord> records, int lastMessageIndex) {
        boolean success = false;
        try {
            List<MemoryRecord> memoryRecords = extractMemories(records);
            if (memoryRecords.isEmpty()) {
                LOGGER.debug("No memories extracted from {} messages", records.size());
                success = true;
                return;
            }

            List<List<Double>> embeddings = generateEmbeddings(memoryRecords);
            if (embeddings.size() != memoryRecords.size()) {
                LOGGER.error("Embedding count mismatch: {} records, {} embeddings",
                    memoryRecords.size(), embeddings.size());
                return;
            }

            memoryStore.saveAll(userId, memoryRecords, embeddings);
            success = true;

            LOGGER.info("Extracted and saved {} memories from {} messages",
                memoryRecords.size(), records.size());

        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
        } finally {
            if (success) {
                extractedIndexMap.put(userId, lastMessageIndex);
            }
        }
    }

    private List<MemoryRecord> extractMemories(List<ChatRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<List<ChatRecord>> chunks = splitByTurns(records);
        List<MemoryRecord> allRecords = new ArrayList<>();

        for (List<ChatRecord> chunk : chunks) {
            List<ChatRecord> truncated = truncateLongMessages(chunk);
            List<MemoryRecord> memoryRecords = extractFromChunk(truncated);
            allRecords.addAll(memoryRecords);
        }

        return allRecords;
    }

    private List<MemoryRecord> extractFromChunk(List<ChatRecord> records) {
        String conversation = formatConversation(records);
        if (conversation.isBlank()) {
            return List.of();
        }

        String prompt = String.format(Prompts.MEMORY_EXTRACTION_PROMPT, conversation);

        try {
            return callLLMForExtraction(prompt);
        } catch (Exception e) {
            LOGGER.error("Failed to extract memories from chunk", e);
            return List.of();
        }
    }

    private List<List<ChatRecord>> splitByTurns(List<ChatRecord> records) {
        List<List<ChatRecord>> chunks = new ArrayList<>();
        List<ChatRecord> currentChunk = new ArrayList<>();
        int turnCount = 0;

        for (ChatRecord record : records) {
            if (record.role() == RoleType.USER) {
                if (turnCount >= MAX_TURNS_PER_EXTRACTION && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk);
                    currentChunk = new ArrayList<>();
                    turnCount = 0;
                }
                turnCount++;
            }
            currentChunk.add(record);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        return chunks;
    }

    private List<ChatRecord> truncateLongMessages(List<ChatRecord> records) {
        return records.stream()
            .map(this::truncateIfNeeded)
            .collect(Collectors.toList());
    }

    private ChatRecord truncateIfNeeded(ChatRecord record) {
        if (record.content() == null || Tokenizer.tokenCount(record.content()) <= MAX_TOKENS_PER_MESSAGE) {
            return record;
        }
        String truncated = Tokenizer.truncate(record.content(), MAX_TOKENS_PER_MESSAGE);
        return new ChatRecord(record.role(), truncated + "\n[truncated]", record.timestamp());
    }

    private String formatConversation(List<ChatRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (ChatRecord record : records) {
            if (record.role() == RoleType.SYSTEM) {
                continue;
            }
            String content = record.content() != null ? record.content() : "";
            if (!content.isBlank()) {
                String role = switch (record.role()) {
                    case USER -> "User";
                    case ASSISTANT -> "Assistant";
                    case TOOL -> "Tool";
                    default -> "Unknown";
                };
                sb.append(role).append(": ").append(content).append('\n');
            }
        }
        return sb.toString();
    }

    private List<MemoryRecord> callLLMForExtraction(String prompt) {
        var messages = List.of(Message.of(RoleType.USER, prompt));
        var request = CompletionRequest.of(messages, null, 0.3, model, "memory-extractor");
        request.responseFormat = ResponseFormat.of(ExtractionResponse.class);
        CompletionResponse response = llmProvider.completion(request);

        if (response == null || response.choices == null || response.choices.isEmpty()) {
            return List.of();
        }

        var choice = response.choices.getFirst();
        if (choice.message == null || choice.message.content == null) {
            return List.of();
        }

        try {
            var extracted = JSON.fromJSON(ExtractionResponse.class, choice.message.content);
            if (extracted.memories == null) {
                return List.of();
            }

            List<MemoryRecord> memoryRecords = new ArrayList<>();
            for (ExtractedMemory mem : extracted.memories) {
                if (mem.content == null || mem.content.isBlank()) {
                    continue;
                }
                double importance = mem.importance != null ? mem.importance : 0.5;
                memoryRecords.add(MemoryRecord.builder()
                    .content(mem.content)
                    .importance(importance)
                    .build());
            }
            return memoryRecords;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse extraction response: {}", choice.message.content, e);
            return List.of();
        }
    }

    private List<List<Double>> generateEmbeddings(List<MemoryRecord> records) {
        List<String> contents = records.stream()
            .map(MemoryRecord::getContent)
            .toList();

        try {
            EmbeddingResponse response = llmProvider.embeddings(new EmbeddingRequest(contents));

            List<List<Double>> embeddings = new ArrayList<>();
            if (response != null && response.embeddings != null) {
                for (var embeddingData : response.embeddings) {
                    if (embeddingData.embedding != null) {
                        embeddings.add(embeddingData.embedding.vectors());
                    }
                }
            }

            if (embeddings.size() != records.size()) {
                LOGGER.warn("Embedding generation returned incomplete results: expected={}, got={}",
                    records.size(), embeddings.size());
            }
            return embeddings;
        } catch (Exception e) {
            LOGGER.error("Failed to generate embeddings for {} memory records", records.size(), e);
            return List.of();
        }
    }

    public static class ExtractionResponse {
        @Property(name = "memories")
        public List<ExtractedMemory> memories;
    }

    public static class ExtractedMemory {
        @Property(name = "content")
        public String content;
        @Property(name = "importance")
        public Double importance;
    }

    public static class Builder {
        private MemoryStore memoryStore;
        private ChatHistoryProvider historyProvider;
        private LLMProvider llmProvider;
        private String model;

        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }

        public Builder historyProvider(ChatHistoryProvider historyProvider) {
            this.historyProvider = historyProvider;
            return this;
        }

        public Builder llmProvider(LLMProvider llmProvider) {
            this.llmProvider = llmProvider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Extraction build() {
            if (memoryStore == null) {
                throw new IllegalStateException("memoryStore is required");
            }
            if (historyProvider == null) {
                throw new IllegalStateException("historyProvider is required");
            }
            if (llmProvider == null) {
                throw new IllegalStateException("llmProvider is required");
            }
            return new Extraction(this);
        }
    }
}
