package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.agent.AgentPersistence;
import ai.core.agent.ExecutionContext;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.cli.log.CliLogger;
import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.Prompts;
import ai.core.session.SessionPersistence;
import ai.core.cli.plugin.PluginManager;
import ai.core.skill.SkillSource;
import ai.core.tool.BuiltinTools;
import ai.core.tool.tools.SkillTool;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extract memories from previous session and persist to markdown files via agent.
 *
 * @author xander
 */
public class SessionMemoryExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMemoryExtractor.class);
    private static final int MAX_TOKENS_PER_MESSAGE = 5000;
    private static final int MIN_MESSAGES_FOR_EXTRACTION = 4;
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

    private static List<SkillSource> buildSkillSources(Path workspace) {
        var home = Path.of(System.getProperty("user.home"), ".core-ai");
        var pluginManager = PluginManager.getInstance(home);
        var sources = new java.util.ArrayList<SkillSource>();
        sources.add(new SkillSource("workspace", workspace.resolve(".core-ai/skills").toString(), 100));
        sources.add(new SkillSource("user", home.resolve("skills").toString(), 50));
        for (var source : pluginManager.getEnabledPluginSkillSources()) {
            sources.add(new SkillSource("plugin:" + source[0], source[1], 75));
        }
        return sources;
    }

    private static void updateSystemMessage(List<Message> messages, String replacement) {
        var systemMsg = messages.getFirst();
        if (systemMsg.content == null || systemMsg.content.isEmpty()) return;
        String text = systemMsg.content.getFirst().text;
        if (text == null) return;
        systemMsg.content = List.of(Content.of(text.replaceAll("(?s)<memories>.*?</memories>", replacement)));
    }

    private final LLMProvider llmProvider;
    private final String model;
    private final SessionPersistence sessionPersistence;
    private final MdMemoryProvider memoryProvider;
    private final Path learningsDir;
    private final Path memoryDir;
    private final Path indexPath;
    private final List<SkillSource> skillSources;

    public SessionMemoryExtractor(MdMemoryProvider memoryProvider, LLMProvider llmProvider,
                                  String model, SessionPersistence sessionPersistence) {
        this.llmProvider = llmProvider;
        this.model = model;
        this.sessionPersistence = sessionPersistence;
        this.memoryProvider = memoryProvider;
        this.memoryDir = memoryProvider.getMemoryDir();
        this.indexPath = memoryDir.getParent().resolve("MEMORY.md");
        this.learningsDir = memoryDir.getParent().resolve(".learnings");
        this.skillSources = buildSkillSources(memoryDir.getParent().getParent());
    }

    public void reloadAgentMemorySection(Agent agent) {
        String fresh = memoryProvider.load();
        String replacement = "<memories>\n" + (fresh.isBlank() ? "(empty)" : fresh) + "\n</memories>";
        String current = agent.getSystemPrompt();
        if (current != null) {
            agent.setSystemPrompt(current.replaceAll("(?s)<memories>.*?</memories>", replacement));
        }
        if (!agent.getMessages().isEmpty()) {
            updateSystemMessage(agent.getMessages(), replacement);
        }
        LOGGER.info("Memory section reloaded in system prompt");
    }

    public boolean hasPendingSessions(String currentSessionId) {
        return sessionPersistence.listSessions().stream()
                .anyMatch(s -> !s.id().equals(currentSessionId) && !isAlreadyExtracted(s.id()));
    }

    public void extractPreviousSessionAsync(String currentSessionId, Runnable onComplete) {
        var sessions = sessionPersistence.listSessions();
        var unextracted = sessions.stream()
                .filter(s -> !s.id().equals(currentSessionId) && !isAlreadyExtracted(s.id()))
                .toList();
        if (unextracted.isEmpty()) return;

        var thread = new Thread(() -> {
            try {
                var allMemories = new java.util.ArrayList<ExtractedMemory>();
                for (var session : unextracted) {
                    allMemories.addAll(extractSessionSafely(session.id()));
                }
                if (!allMemories.isEmpty()) {
                    writeMemoriesViaAgent(allMemories);
                }
                if (Files.isDirectory(learningsDir)) {
                    promotePendingLearnings();
                }
                if (onComplete != null) onComplete.run();
            } catch (Exception e) {
                LOGGER.warn("Background memory extraction failed: {}", e.getMessage());
            }
        }, "memory-extractor");
        thread.setDaemon(true);
        thread.start();
    }

    private List<ExtractedMemory> extractSessionSafely(String sessionId) {
        LOGGER.info("Extracting memories from session {}", sessionId);
        try {
            var memories = extractFromSession(sessionId);
            markExtracted(sessionId);
            LOGGER.info("Extracted {} memories from session {}", memories.size(), sessionId);
            return memories;
        } catch (Exception e) {
            LOGGER.warn("Failed to extract memories from session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private List<ExtractedMemory> extractFromSession(String sessionId) {
        var messages = loadSessionMessages(sessionId);
        if (messages == null || messages.isEmpty()) return List.of();
        return extractFromMessages(messages);
    }

    List<ExtractedMemory> extractFromMessages(List<Message> messages) {
        var userAssistantMessages = messages.stream()
                .filter(m -> m.role == RoleType.USER || m.role == RoleType.ASSISTANT)
                .toList();
        if (userAssistantMessages.size() < MIN_MESSAGES_FOR_EXTRACTION) {
            LOGGER.debug("Too few messages ({}) for extraction, skipping", userAssistantMessages.size());
            return List.of();
        }
        String conversation = formatConversation(userAssistantMessages);
        if (conversation.isBlank()) return List.of();
        try {
            var extracted = callLLMForExtraction(conversation);
            LOGGER.debug("Extracted {} memories from messages", extracted.size());
            return extracted;
        } catch (Exception e) {
            LOGGER.warn("Failed to extract memories from messages: {}", e.getMessage());
            return List.of();
        }
    }

    private void writeMemoriesViaAgent(List<ExtractedMemory> memories) {
        if (memories.isEmpty()) return;
        String prompt = String.format(Prompts.MEMORY_WRITER_PROMPT,
                memoryDir.toAbsolutePath(),
                indexPath.toAbsolutePath(),
                JsonUtil.toJson(memories));
        runAgent(prompt, "memory-writer", BuiltinTools.FILE_OPERATIONS, buildWriterSystemPrompt());
    }

    private String buildWriterSystemPrompt() {
        String index = indexPath.toAbsolutePath().toString();
        String topics = memoryDir.toAbsolutePath().toString();
        String existing = memoryProvider.load();
        return """
                ## Memory

                Index: %s | Topic files: %s/*.md
                Each topic file has YAML frontmatter (name, description, type: user/feedback/project/reference).

                Index structure: | File | Description | Created | Updated |
                Description column: use the `description` field from the file's YAML frontmatter.

                <memories>
                %s
                </memories>
                """.formatted(index, topics, existing.isBlank() ? "(empty)" : existing);
    }

    private void promotePendingLearnings() {
        if (!Files.isDirectory(learningsDir)) return;
        String prompt = String.format(Prompts.LEARNINGS_PROMOTER_PROMPT,
                learningsDir.toAbsolutePath(),
                memoryDir.toAbsolutePath(),
                indexPath.toAbsolutePath());
        var tools = new java.util.ArrayList<>(BuiltinTools.FILE_OPERATIONS);
        tools.add(SkillTool.builder()
                .sources(skillSources)
                .workspaceDir(memoryDir.getParent().toAbsolutePath().toString())
                .build());
        runAgent(prompt, "learnings-promoter", tools, null);
    }

    private void runAgent(String prompt, String agentName, List<ai.core.tool.ToolCall> tools, String systemPrompt) {
        try {
            Files.createDirectories(memoryDir);
            var logBuffer = new StringBuilder("[").append(agentName).append("] ");
            StreamingCallback logCallback = new StreamingCallback() {
                @Override
                public void onChunk(String chunk) {
                    logBuffer.append(chunk);
                }

                @Override
                public void onError(Throwable error) {
                    CliLogger.writeToFileDirect("[" + agentName + "] streaming error: " + error.getMessage(), error);
                }
            };
            var builder = Agent.builder()
                    .llmProvider(llmProvider)
                    .model(model)
                    .toolCalls(tools)
                    .maxTurn(20)
                    .temperature(0.3)
                    .streamingCallback(logCallback);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                builder.systemPrompt(systemPrompt);
            }
            var agent = builder.build();
            agent.setExecutionContext(ExecutionContext.builder().build());
            agent.run(prompt);
            CliLogger.writeToFileDirect(logBuffer.toString(), null);
        } catch (Exception e) {
            LOGGER.warn("Agent {} failed: {}", agentName, e.getMessage());
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
            Path file = memoryDir.resolve(EXTRACTED_SESSIONS_FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, sessionId + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Failed to mark session as extracted: {}", e.getMessage());
        }
    }

    private Set<String> loadExtractedSessions() {
        Path file = memoryDir.resolve(EXTRACTED_SESSIONS_FILE);
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
                    .filter(m -> m.importance == null || m.importance >= 0.6)
                    .toList();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse extraction response", e);
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
        @Property(name = "type")
        public String type;
        @Property(name = "topic")
        public String topic;
        @Property(name = "importance")
        public Double importance;
    }
}
