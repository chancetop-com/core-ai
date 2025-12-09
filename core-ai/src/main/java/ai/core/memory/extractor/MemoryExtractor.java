package ai.core.memory.extractor;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.memory.model.EpisodicMemoryEntry;
import ai.core.memory.model.MemoryEntry;
import ai.core.memory.model.SemanticCategory;
import ai.core.memory.model.SemanticMemoryEntry;
import ai.core.memory.util.MemoryUtils;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Memory extractor for Phase 1 of the Two-Phase Pipeline.
 * Extracts candidate memories from conversation using LLM.
 *
 * @author xander
 */
public class MemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final String EXTRACTION_PROMPT = """
        # Memory Extraction Task

        Analyze the following conversation and extract key memories about the user.

        ## Conversation
        %s

        ## Instructions
        Extract memories in these categories:

        1. **SEMANTIC** - Facts, preferences, and knowledge about the user:
           - FACT: Objective information (name, location, job, etc.)
           - PREFERENCE: Likes and dislikes
           - KNOWLEDGE: Expertise, interests, skills

        2. **EPISODIC** - Significant events or experiences:
           - Situation: What was happening
           - Action: What was done
           - Outcome: The result (optional)

        ## Important
        - IGNORE trivial information, greetings, small talk
        - IGNORE temporary context (weather today, busy this afternoon)
        - Focus on LONG-TERM relevant information
        - Be CONCISE and SPECIFIC
        - Rate importance from 0.0 to 1.0 (1.0 = very important)

        ## Output Format (JSON)
        {
            "memories": [
                {
                    "type": "SEMANTIC",
                    "category": "FACT|PREFERENCE|KNOWLEDGE",
                    "content": "concise memory content",
                    "subject": "main subject/entity",
                    "predicate": "relationship/action",
                    "object": "value/target",
                    "importance": 0.0-1.0
                },
                {
                    "type": "EPISODIC",
                    "content": "event description",
                    "situation": "context",
                    "action": "what happened",
                    "outcome": "result",
                    "importance": 0.0-1.0
                }
            ]
        }

        If NO significant memories found, return: {"memories": []}

        Output JSON only:
        """;

    private final LLMProvider llmProvider;
    private final String model;

    public MemoryExtractor(LLMProvider llmProvider, String model) {
        this.llmProvider = llmProvider;
        this.model = model;
    }

    /**
     * Extract candidate memories from conversation messages.
     *
     * @param messages conversation messages
     * @param userId   user identifier
     * @return list of extracted memory entries
     */
    public List<MemoryEntry> extract(List<Message> messages, String userId) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        String conversationText = formatMessages(messages);
        String prompt = String.format(EXTRACTION_PROMPT, conversationText);

        try {
            var response = llmProvider.completion(CompletionRequest.of(
                List.of(Message.of(RoleType.USER, prompt)),
                null, 0.3, model, "memory-extractor"
            ));

            if (response == null || response.choices == null || response.choices.isEmpty()) {
                return List.of();
            }

            String content = response.choices.getFirst().message.content;
            return parseMemories(content, userId);
        } catch (Exception e) {
            LOGGER.error("Failed to extract memories: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Extract memories from a single message pair (user + assistant).
     */
    public List<MemoryEntry> extractFromExchange(String userMessage, String assistantMessage, String userId) {
        var messages = new ArrayList<Message>();
        messages.add(Message.of(RoleType.USER, userMessage));
        if (assistantMessage != null) {
            messages.add(Message.of(RoleType.ASSISTANT, assistantMessage));
        }
        return extract(messages, userId);
    }

    private String formatMessages(List<Message> messages) {
        var sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.role == RoleType.SYSTEM) continue;
            String role = switch (msg.role) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case TOOL -> "Tool";
                default -> "Unknown";
            };
            sb.append(role).append(": ").append(msg.content != null ? msg.content : "").append('\n');
        }
        return sb.toString();
    }

    private List<MemoryEntry> parseMemories(String jsonContent, String userId) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return List.of();
        }

        // Clean up JSON if wrapped in markdown code blocks
        String cleanedJson = MemoryUtils.cleanJson(jsonContent);

        try {
            var dto = JSON.fromJSON(ExtractionResultDto.class, cleanedJson);
            if (dto.memories == null || dto.memories.isEmpty()) {
                return List.of();
            }

            var result = new ArrayList<MemoryEntry>();
            for (var memDto : dto.memories) {
                MemoryEntry entry = toMemoryEntry(memDto, userId);
                if (entry != null) {
                    result.add(entry);
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse extraction result: {}", e.getMessage());
            return List.of();
        }
    }

    private MemoryEntry toMemoryEntry(MemoryDto dto, String userId) {
        if (dto.content == null || dto.content.isBlank()) {
            return null;
        }

        if ("SEMANTIC".equalsIgnoreCase(dto.type)) {
            return SemanticMemoryEntry.builder()
                .userId(userId)
                .content(dto.content)
                .category(parseCategory(dto.category))
                .subject(dto.subject)
                .predicate(dto.predicate)
                .object(dto.object)
                .importance(dto.importance != null ? dto.importance : 0.5)
                .build();
        } else if ("EPISODIC".equalsIgnoreCase(dto.type)) {
            return EpisodicMemoryEntry.builder()
                .userId(userId)
                .content(dto.content)
                .situation(dto.situation)
                .action(dto.action)
                .outcome(dto.outcome)
                .importance(dto.importance != null ? dto.importance : 0.5)
                .build();
        }

        return null;
    }

    private SemanticCategory parseCategory(String category) {
        if (category == null) return SemanticCategory.FACT;
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "PREFERENCE" -> SemanticCategory.PREFERENCE;
            case "KNOWLEDGE" -> SemanticCategory.KNOWLEDGE;
            default -> SemanticCategory.FACT;
        };
    }

    // DTOs for JSON parsing
    public static class ExtractionResultDto {
        @Property(name = "memories")
        public List<MemoryDto> memories;
    }

    public static class MemoryDto {
        @Property(name = "type")
        public String type;

        @Property(name = "category")
        public String category;

        @Property(name = "content")
        public String content;

        @Property(name = "subject")
        public String subject;

        @Property(name = "predicate")
        public String predicate;

        @Property(name = "object")
        public String object;

        @Property(name = "situation")
        public String situation;

        @Property(name = "action")
        public String action;

        @Property(name = "outcome")
        public String outcome;

        @Property(name = "importance")
        public Double importance;
    }
}
