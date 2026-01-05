package ai.core.memory.longterm;

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

/**
 * @author xander
 */
public class DefaultMemoryExtractor implements MemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMemoryExtractor.class);
    //todo
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<ExtractedMemory>> EXTRACTION_TYPE_REF = new TypeReference<>() { };

    private final LLMProvider llmProvider;
    private final String model;
    private final String extractionPrompt;

    public DefaultMemoryExtractor(LLMProvider llmProvider) {
        this(llmProvider, null, null);
    }

    public DefaultMemoryExtractor(LLMProvider llmProvider, String model) {
        this(llmProvider, model, null);
    }

    public DefaultMemoryExtractor(LLMProvider llmProvider, String model, String customPrompt) {
        this.llmProvider = llmProvider;
        this.model = model;
        this.extractionPrompt = customPrompt != null ? customPrompt : Prompts.LONG_TERM_MEMORY_EXTRACTION_PROMPT;
    }

    @Override
    public List<MemoryRecord> extract(MemoryScope scope, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        String conversation = formatConversation(messages);
        if (conversation.isBlank()) {
            return List.of();
        }

        String prompt = String.format(extractionPrompt, conversation);

        try {
            String response = callLLM(prompt);
            return parseResponse(scope, response);
        } catch (Exception e) {
            LOGGER.error("Failed to extract memories", e);
            return List.of();
        }
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

    private List<MemoryRecord> parseResponse(MemoryScope scope, String response) {
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
                    .scope(scope)
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
