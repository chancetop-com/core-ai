package ai.core.cli.memory;

import ai.core.agent.AgentPersistence;
import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.Prompts;
import ai.core.session.SessionPersistence;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

/**
 * Extract memories from previous session and persist to markdown files.
 *
 * @author xander
 */
public class SessionMemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMemoryExtractor.class);
    private static final int MAX_TOKENS_PER_MESSAGE = 5000;
    private static final int MIN_MESSAGES_FOR_EXTRACTION = 4;
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String EXTRACTED_SESSIONS_FILE = ".extracted-sessions";

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
    private final SessionPersistence sessionPersistence;

    public SessionMemoryExtractor(MdMemoryProvider memoryProvider, LLMProvider llmProvider,
                                  String model, SessionPersistence sessionPersistence) {
        this.memoryProvider = memoryProvider;
        this.llmProvider = llmProvider;
        this.model = model;
        this.sessionPersistence = sessionPersistence;
    }

    public void extractPreviousSessionAsync(String currentSessionId, TerminalUI ui) {
        var sessions = sessionPersistence.listSessions();
        String previousId = null;
        for (var s : sessions) {
            if (!s.id().equals(currentSessionId)) {
                previousId = s.id();
                break;
            }
        }
        if (previousId == null || isAlreadyExtracted(previousId)) return;

        ui.printStreamingChunk(AnsiTheme.MUTED + "  Extracting memories from last session..." + AnsiTheme.RESET + "\n");
        String targetSessionId = previousId;
        var thread = new Thread(() -> {
            try {
                var messages = loadSessionMessages(targetSessionId);
                if (messages == null || messages.isEmpty()) return;
                int count = extract(messages);
                markExtracted(targetSessionId);
                if (count > 0) {
                    ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                            + " Extracted " + count + " memories from last session\n");
                }
            } catch (Exception e) {
                LOGGER.warn("Background memory extraction failed: {}", e.getMessage());
            }
        }, "memory-extractor");
        thread.setDaemon(true);
        thread.start();
    }

    int extract(List<Message> messages) {
        var userAssistantMessages = messages.stream()
                .filter(m -> m.role == RoleType.USER || m.role == RoleType.ASSISTANT)
                .toList();
        if (userAssistantMessages.size() < MIN_MESSAGES_FOR_EXTRACTION) {
            LOGGER.debug("Too few messages ({}) for extraction, skipping", userAssistantMessages.size());
            return 0;
        }
        String conversation = formatConversation(userAssistantMessages);
        if (conversation.isBlank()) return 0;
        try {
            var extracted = callLLMForExtraction(conversation);
            if (extracted.isEmpty()) {
                LOGGER.debug("No memories extracted from session");
                return 0;
            }
            writeMemories(extracted);
            LOGGER.info("Extracted and saved {} memories from session", extracted.size());
            return extracted.size();
        } catch (Exception e) {
            LOGGER.warn("Failed to extract session memories: {}", e.getMessage());
            return 0;
        }
    }

    private List<Message> loadSessionMessages(String sessionId) {
        return sessionPersistence.load(sessionId)
                .map(data -> {
                    var domain = JsonUtil.fromJson(AgentPersistence.AgentPersistenceDomain.class, data);
                    return domain.messages;
                })
                .orElse(null);
    }

    private boolean isAlreadyExtracted(String sessionId) {
        return loadExtractedSessions().contains(sessionId);
    }

    private void markExtracted(String sessionId) {
        try {
            Path file = memoryProvider.getMemoryDir().resolve(EXTRACTED_SESSIONS_FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, sessionId + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Failed to mark session as extracted: {}", e.getMessage());
        }
    }

    private Set<String> loadExtractedSessions() {
        Path file = memoryProvider.getMemoryDir().resolve(EXTRACTED_SESSIONS_FILE);
        if (!Files.exists(file)) return Set.of();
        try {
            return new HashSet<>(Files.readAllLines(file));
        } catch (IOException e) {
            return Set.of();
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
        if (choice.message == null || choice.message.content == null || choice.message.content.isBlank()) {
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
