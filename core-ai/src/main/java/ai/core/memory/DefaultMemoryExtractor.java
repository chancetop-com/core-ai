package ai.core.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.memory.extraction.MemoryExtractor;
import ai.core.memory.history.ChatRecord;
import ai.core.prompt.Prompts;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author xander
 */
public class DefaultMemoryExtractor implements MemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMemoryExtractor.class);
    private static final int DEFAULT_MAX_TURNS_PER_EXTRACTION = 5;
    private static final int DEFAULT_MAX_TOKENS_PER_MESSAGE = 1000;

    private final LLMProvider llmProvider;
    private final String model;
    private final String extractionPrompt;
    private final int maxTurnsPerExtraction;
    private final int maxTokensPerMessage;

    public DefaultMemoryExtractor(LLMProvider llmProvider) {
        this(llmProvider, null, null, DEFAULT_MAX_TURNS_PER_EXTRACTION, DEFAULT_MAX_TOKENS_PER_MESSAGE);
    }

    public DefaultMemoryExtractor(LLMProvider llmProvider, String model) {
        this(llmProvider, model, null, DEFAULT_MAX_TURNS_PER_EXTRACTION, DEFAULT_MAX_TOKENS_PER_MESSAGE);
    }

    public DefaultMemoryExtractor(LLMProvider llmProvider, String model, String customPrompt) {
        this(llmProvider, model, customPrompt, DEFAULT_MAX_TURNS_PER_EXTRACTION, DEFAULT_MAX_TOKENS_PER_MESSAGE);
    }

    public DefaultMemoryExtractor(LLMProvider llmProvider,
                                   String model,
                                   String customPrompt,
                                   int maxTurnsPerExtraction,
                                   int maxTokensPerMessage) {
        this.llmProvider = llmProvider;
        this.model = model;
        this.extractionPrompt = customPrompt != null ? customPrompt : Prompts.LONG_TERM_MEMORY_EXTRACTION_PROMPT;
        this.maxTurnsPerExtraction = maxTurnsPerExtraction;
        this.maxTokensPerMessage = maxTokensPerMessage;
    }

    @Override
    public List<MemoryRecord> extract(List<ChatRecord> records) {
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

        String prompt = String.format(extractionPrompt, conversation);

        try {
            return callLLM(prompt);
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
                if (turnCount >= maxTurnsPerExtraction && !currentChunk.isEmpty()) {
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
        if (record.content() == null || Tokenizer.tokenCount(record.content()) <= maxTokensPerMessage) {
            return record;
        }
        String truncated = Tokenizer.truncate(record.content(), maxTokensPerMessage);
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

    private List<MemoryRecord> callLLM(String prompt) {
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
}
