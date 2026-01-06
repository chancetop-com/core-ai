package ai.core.memory.longterm;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import ai.core.prompt.Prompts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<ExtractedMemory>> EXTRACTION_TYPE_REF = new TypeReference<>() { };

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
    public List<MemoryRecord> extract(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<List<Message>> chunks = splitByTurns(messages);
        List<MemoryRecord> allRecords = new ArrayList<>();

        for (List<Message> chunk : chunks) {
            List<Message> truncated = truncateLongMessages(chunk);
            List<MemoryRecord> records = extractFromChunk(truncated);
            allRecords.addAll(records);
        }

        return allRecords;
    }

    private List<MemoryRecord> extractFromChunk(List<Message> messages) {
        String conversation = formatConversation(messages);
        if (conversation.isBlank()) {
            return List.of();
        }

        String prompt = String.format(extractionPrompt, conversation);

        try {
            String response = callLLM(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            LOGGER.error("Failed to extract memories from chunk", e);
            return List.of();
        }
    }

    private List<List<Message>> splitByTurns(List<Message> messages) {
        List<List<Message>> chunks = new ArrayList<>();
        List<Message> currentChunk = new ArrayList<>();
        int turnCount = 0;

        for (Message msg : messages) {
            if (msg.role == RoleType.USER) {
                if (turnCount >= maxTurnsPerExtraction && !currentChunk.isEmpty()) {
                    chunks.add(currentChunk);
                    currentChunk = new ArrayList<>();
                    turnCount = 0;
                }
                turnCount++;
            }
            currentChunk.add(msg);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        return chunks;
    }

    private List<Message> truncateLongMessages(List<Message> messages) {
        return messages.stream()
            .map(this::truncateIfNeeded)
            .collect(Collectors.toList());
    }

    private Message truncateIfNeeded(Message msg) {
        if (msg.content == null || Tokenizer.tokenCount(msg.content) <= maxTokensPerMessage) {
            return msg;
        }
        String truncated = Tokenizer.truncate(msg.content, maxTokensPerMessage);
        return Message.of(msg.role, truncated + "\n[truncated]");
    }

    private String formatConversation(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.role == RoleType.SYSTEM) {
                continue;
            }
            String content = msg.content != null ? msg.content : "";
            if (!content.isBlank()) {
                String role = switch (msg.role) {
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

    private String callLLM(String prompt) {
        var messages = List.of(Message.of(RoleType.USER, prompt));
        var request = CompletionRequest.of(messages, null, 0.3, model, "memory-extractor");
        CompletionResponse response = llmProvider.completion(request);

        if (response != null && response.choices != null && !response.choices.isEmpty()) {
            var choice = response.choices.getFirst();
            if (choice.message != null && choice.message.content != null) {
                return choice.message.content.trim();
            }
        }
        return "[]";
    }

    private List<MemoryRecord> parseResponse(String response) {
        List<MemoryRecord> records = new ArrayList<>();
        try {
            String json = extractJson(response);
            var extracted = OBJECT_MAPPER.readValue(json, EXTRACTION_TYPE_REF);

            for (ExtractedMemory mem : extracted) {
                if (mem.content == null || mem.content.isBlank()) {
                    continue;
                }

                double importance = mem.importance != null ? mem.importance : 0.5;

                MemoryRecord record = MemoryRecord.builder()
                    .content(mem.content)
                    .importance(importance)
                    .build();

                records.add(record);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse extraction response: {}", response, e);
        }

        return records;
    }

    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "[]";
        }

        String text = stripMarkdownCodeBlock(response.trim());

        // Find JSON array boundaries
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        LOGGER.debug("No valid JSON array found in response: {}", truncateForLog(response));
        return "[]";
    }

    private String stripMarkdownCodeBlock(String text) {
        if (!text.contains("```")) {
            return text;
        }
        int codeStart = text.indexOf("```");
        int codeEnd = text.lastIndexOf("```");
        if (codeEnd <= codeStart) {
            return text;
        }
        String inner = text.substring(codeStart + 3, codeEnd).trim();
        // Remove language identifier like "json"
        return inner.startsWith("json") ? inner.substring(4).trim() : inner;
    }

    private String truncateForLog(String text) {
        if (text == null) return "";
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    public static class ExtractedMemory {
        public String content;
        public Double importance;
    }
}
