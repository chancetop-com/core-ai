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
import java.util.List;
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

    private static String stripMarkdownCodeBlock(String text) {
        var stripped = text.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline > 0) stripped = stripped.substring(firstNewline + 1);
            if (stripped.endsWith("```")) stripped = stripped.substring(0, stripped.length() - 3).strip();
        }
        return stripped;
    }

    private final MdMemoryProvider memoryProvider;
    private final LLMProvider llmProvider;
    private final String model;

    public SessionMemoryExtractor(MdMemoryProvider memoryProvider, LLMProvider llmProvider, String model) {
        this.memoryProvider = memoryProvider;
        this.llmProvider = llmProvider;
        this.model = model;
    }

    public Thread extractAsync(List<Message> messages) {
        var copy = new ArrayList<>(messages);
        var thread = new Thread(() -> extract(copy), "memory-extractor");
        thread.setDaemon(false);
        thread.start();
        return thread;
    }

    public void extract(List<Message> messages) {
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

    private String formatConversation(List<Message> messages) {
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

    private List<ExtractedMemory> callLLMForExtraction(String conversation) {
        String prompt = String.format(Prompts.MEMORY_EXTRACTION_PROMPT, conversation);
        var request = CompletionRequest.of(
                List.of(Message.of(RoleType.USER, prompt)),
                null, 0.3, model, "memory-extractor");
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
            String json = stripMarkdownCodeBlock(choice.message.content);
            List<ExtractedMemory> memories;
            if (json.stripLeading().startsWith("[")) {
                memories = JsonUtil.fromJson(new com.fasterxml.jackson.core.type.TypeReference<>() { }, json);
            } else {
                var result = JsonUtil.fromJson(ExtractionResponse.class, json);
                memories = result.memories;
            }
            if (memories == null) return List.of();
            return memories.stream()
                    .filter(m -> m.content != null && !m.content.isBlank())
                    .filter(m -> m.importance == null || m.importance >= 0.5)
                    .toList();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse extraction response", e);
            return List.of();
        }
    }

    private void writeMemories(List<ExtractedMemory> memories) throws IOException {
        Path memoryDir = memoryProvider.getMemoryDir();
        Files.createDirectories(memoryDir);

        var existingMemories = memoryProvider.load();
        var newMemories = new ArrayList<ExtractedMemory>();
        for (var mem : memories) {
            String prefix = mem.content.toLowerCase(Locale.ROOT).substring(0, Math.min(30, mem.content.length()));
            if (!existingMemories.toLowerCase(Locale.ROOT).contains(prefix)) {
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

        var sb = new StringBuilder(256);
        sb.append("---\nname: session-extraction-").append(timestamp)
          .append("\ndescription: Auto-extracted memories from session conversation\ntype: project\n---\n\n");
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
        public List<ExtractedMemory> memories;
    }

    public static class ExtractedMemory {
        @Property(name = "content")
        public String content;
        @Property(name = "importance")
        public Double importance;
    }
}
