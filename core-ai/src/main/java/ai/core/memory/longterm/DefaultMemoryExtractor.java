package ai.core.memory.longterm;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.longterm.extraction.MemoryExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author xander
 */
public class DefaultMemoryExtractor implements MemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMemoryExtractor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String EXTRACTION_PROMPT = """
        Analyze the following conversation and extract memorable information about the user.

        Extract these types of information:
        - FACT: Factual information about the user (e.g., "works as a software engineer", "lives in Tokyo")
        - PREFERENCE: User preferences and likes/dislikes (e.g., "prefers dark mode", "likes coffee")
        - GOAL: User goals or intentions (e.g., "wants to learn AI", "planning to travel")
        - EPISODE: Notable events or experiences mentioned (e.g., "attended a conference last week")
        - RELATIONSHIP: Information about people the user knows (e.g., "has a colleague named John")

        Conversation:
        %s

        Return a JSON array of extracted memories. Each memory should have:
        - "content": the extracted information as a clear, standalone statement
        - "type": one of FACT, PREFERENCE, GOAL, EPISODE, RELATIONSHIP
        - "importance": a number from 0.0 to 1.0 indicating importance

        Only extract meaningful, non-trivial information. Skip greetings and small talk.
        If no meaningful information can be extracted, return an empty array: []

        Response format:
        [{"content": "...", "type": "...", "importance": 0.8}, ...]
        """;

    private static final TypeReference<List<ExtractedMemory>> EXTRACTION_TYPE_REF = new TypeReference<>() { };

    private final LLMProvider llmProvider;
    private final String model;

    public DefaultMemoryExtractor(LLMProvider llmProvider) {
        this(llmProvider, null);
    }

    public DefaultMemoryExtractor(LLMProvider llmProvider, String model) {
        this.llmProvider = llmProvider;
        this.model = model;
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

        String prompt = String.format(EXTRACTION_PROMPT, conversation);

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
            List<ExtractedMemory> extracted = OBJECT_MAPPER.readValue(json, EXTRACTION_TYPE_REF);

            for (ExtractedMemory mem : extracted) {
                if (mem.content == null || mem.content.isBlank()) {
                    continue;
                }

                MemoryType type = parseMemoryType(mem.type);
                double importance = mem.importance != null ? mem.importance : 0.5;

                MemoryRecord record = MemoryRecord.builder()
                    .scope(scope)
                    .content(mem.content)
                    .type(type)
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
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return "[]";
    }

    private MemoryType parseMemoryType(String type) {
        if (type == null) {
            return MemoryType.FACT;
        }
        try {
            return MemoryType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryType.FACT;
        }
    }

    public static class ExtractedMemory {
        public String content;
        public String type;
        public Double importance;
    }
}
