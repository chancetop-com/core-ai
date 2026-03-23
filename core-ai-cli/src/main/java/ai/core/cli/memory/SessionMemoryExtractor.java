package ai.core.cli.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.Prompts;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Extract memories from session conversation and persist to markdown files.
 *
 * @author xander
 */
public class SessionMemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMemoryExtractor.class);
    private static final int MAX_TOKENS_PER_MESSAGE = 5000;
    private static final int MIN_MESSAGES_FOR_EXTRACTION = 4;
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MdMemoryProvider memoryProvider;
    private final LLMProvider llmProvider;
    private final String model;

    public SessionMemoryExtractor(MdMemoryProvider memoryProvider, LLMProvider llmProvider, String model) {
        this.memoryProvider = memoryProvider;
        this.llmProvider = llmProvider;
        this.model = model;
    }

    public void extract(java.util.List<ai.core.llm.domain.Message> messages) {
        var userAssistantMessages = messages.stream()
                .filter(m -> m.role == RoleType.USER || m.role == RoleType.ASSISTANT)
                .toList();
        if (userAssistantMessages.size() < MIN_MESSAGES_FOR_EXTRACTION) {
            LOGGER.debug("Too few messages ({}) for extraction, skipping", userAssistantMessages.size());
            return;
        }

        String conversation = formatConversation(userAssistantMessages);
        if (conversation.isBlank()) return;

        try {
            var extracted = callLLMForExtraction(conversation);
            if (extracted.isEmpty()) {
                LOGGER.debug("No memories extracted from session");
                return;
            }
            writeMemories(extracted);
            LOGGER.info("Extracted and saved {} memories from session", extracted.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to extract session memories: {}", e.getMessage());
        }
    }

    private String formatConversation(java.util.List<ai.core.llm.domain.Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) continue;
            if (Tokenizer.tokenCount(text) > MAX_TOKENS_PER_MESSAGE) {
                text = Tokenizer.truncate(text, MAX_TOKENS_PER_MESSAGE) + "\n[truncated]";
            }
            String role = msg.role == RoleType.USER ? "User" : "Assistant";
            sb.append(role).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    private java.util.List<ExtractedMemory> callLLMForExtraction(String conversation) {
        String prompt = String.format(Prompts.MEMORY_EXTRACTION_PROMPT, conversation);
        var request = CompletionRequest.of(
                java.util.List.of(Message.of(RoleType.USER, prompt)),
                null, 0.3, model, "memory-extractor");
        request.responseFormat = ResponseFormat.of(ExtractionResponse.class);

        CompletionResponse response = llmProvider.completion(request);
        if (response == null || response.choices == null || response.choices.isEmpty()) {
            return java.util.List.of();
        }

        var choice = response.choices.getFirst();
        if (choice.message == null || choice.message.content == null) {
            return java.util.List.of();
        }

        try {
            var result = JsonUtil.fromJson(ExtractionResponse.class, choice.message.content);
            if (result.memories == null) return java.util.List.of();
            return result.memories.stream()
                    .filter(m -> m.content != null && !m.content.isBlank())
                    .filter(m -> m.importance == null || m.importance >= 0.5)
                    .toList();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse extraction response", e);
            return java.util.List.of();
        }
    }

    private void writeMemories(java.util.List<ExtractedMemory> memories) throws IOException {
        Path memoryDir = memoryProvider.getMemoryDir();
        Files.createDirectories(memoryDir);

        var existingMemories = memoryProvider.load();
        var newMemories = new ArrayList<ExtractedMemory>();
        for (var mem : memories) {
            if (!existingMemories.toLowerCase(Locale.ROOT).contains(mem.content.toLowerCase(Locale.ROOT).substring(0, Math.min(30, mem.content.length())))) {
                newMemories.add(mem);
            }
        }
        if (newMemories.isEmpty()) {
            LOGGER.debug("All extracted memories already exist, skipping");
            return;
        }

        String timestamp = FILE_DATE_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()));
        String fileName = "extracted-" + timestamp + ".md";
        Path filePath = memoryDir.resolve(fileName);

        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: session-extraction-").append(timestamp).append('\n');
        sb.append("description: Auto-extracted memories from session conversation\n");
        sb.append("type: project\n");
        sb.append("---\n\n");

        for (var mem : newMemories) {
            sb.append("- ").append(mem.content).append('\n');
        }

        Files.writeString(filePath, sb.toString());

        updateIndex(fileName);
    }

    private void updateIndex(String fileName) throws IOException {
        Path indexPath = memoryProvider.getMemoryDir().getParent().resolve("MEMORY.md");
        String existing = Files.exists(indexPath) ? Files.readString(indexPath) : "";
        if (existing.contains(fileName)) return;

        String entry = "\n- [" + fileName + "](memory/" + fileName + ") - Auto-extracted session memories\n";
        Files.writeString(indexPath, existing + entry);
    }

    public static class ExtractionResponse {
        @Property(name = "memories")
        public java.util.List<ExtractedMemory> memories;
    }

    public static class ExtractedMemory {
        @Property(name = "content")
        public String content;
        @Property(name = "importance")
        public Double importance;
    }
}
