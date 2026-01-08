package ai.core.compression;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
public class Compression {
    private static final Logger LOGGER = LoggerFactory.getLogger(Compression.class);

    private static final double DEFAULT_TRIGGER_THRESHOLD = 0.8;
    private static final int MAX_TOOL_RESULT_TOKENS = 30000;
    private static final int HEAD_TOKENS = 500;
    private static final int TAIL_TOKENS = 500;
    private static final String TEMP_DIR_NAME = "core-ai";
    private static final int DEFAULT_KEEP_RECENT_TURNS = 5;
    private static final int MIN_SUMMARY_TOKENS = 500;
    private static final int MAX_SUMMARY_TOKENS = 4000;
    private final double triggerThreshold;
    private final int keepRecentTurns;
    private final int maxContextTokens;
    private final LLMProvider llmProvider;
    private final String model;

    public Compression(LLMProvider llmProvider, String model) {
        this(DEFAULT_TRIGGER_THRESHOLD, DEFAULT_KEEP_RECENT_TURNS, llmProvider, model);
    }

    public Compression(double triggerThreshold, int keepRecentTurns, LLMProvider llmProvider, String model) {
        this.triggerThreshold = triggerThreshold;
        this.keepRecentTurns = keepRecentTurns;
        this.llmProvider = llmProvider;
        this.model = model;
        this.maxContextTokens = LLMModelContextRegistry.getInstance().getMaxInputTokens(model);
    }

    public boolean shouldCompress(int currentTokens) {
        if (llmProvider == null) {
            return false;
        }
        return currentTokens >= (int) (maxContextTokens * triggerThreshold);
    }

    public List<Message> compress(List<Message> messages) {
        if (messages == null || messages.isEmpty() || llmProvider == null) {
            return messages;
        }

        int currentTokens = MessageTokenCounter.count(messages);
        if (!shouldCompress(currentTokens)) {
            return messages;
        }

        LOGGER.info("Compressing messages: currentTokens={}, threshold={}", currentTokens, (int) (maxContextTokens * triggerThreshold));

        return doCompress(messages);
    }

    private List<Message> doCompress(List<Message> messages) {
        var systemMsg = extractSystemMessage(messages);
        var conversationMsgs = extractConversationMessages(messages);

        if (conversationMsgs.size() <= 2) {
            LOGGER.debug("Not enough messages to compress, keeping all");
            return messages;
        }

        int keepFromIndex = calculateKeepFromIndex(conversationMsgs);
        if (keepFromIndex <= 0) {
            LOGGER.warn("No messages to compress after calculation");
            return messages;
        }

        var toCompress = new ArrayList<>(conversationMsgs.subList(0, keepFromIndex));
        var toKeep = new ArrayList<>(conversationMsgs.subList(keepFromIndex, conversationMsgs.size()));

        if (toCompress.isEmpty()) {
            LOGGER.debug("Nothing to compress, keeping all messages");
            return messages;
        }

        String summary = summarize(toCompress);
        if (summary.isBlank()) {
            LOGGER.warn("Summarization returned empty result, keeping original messages");
            return messages;
        }

        List<Message> result = buildCompressedResult(systemMsg, summary, toKeep);
        int newTokens = MessageTokenCounter.count(result);
        LOGGER.info("Compression complete: {} -> {} tokens, {} -> {} messages",
            MessageTokenCounter.count(messages), newTokens, messages.size(), result.size());

        return result;
    }

    private List<Message> buildCompressedResult(Message systemMsg, String summary, List<Message> toKeep) {
        var toolCallId = "memory_compress_" + System.currentTimeMillis();
        FunctionCall compressCall = FunctionCall.of(toolCallId, "function", "memory_compress", "{}");
        var toolCallMsg = Message.of(RoleType.ASSISTANT, null, null, null, null, List.of(compressCall));
        Message toolResultMsg = Message.of(RoleType.TOOL, summary, "memory_compress", toolCallId, null, null);

        List<Message> result = new ArrayList<>();
        if (systemMsg != null) {
            result.add(systemMsg);
        }
        result.add(toolCallMsg);
        result.add(toolResultMsg);
        result.addAll(toKeep);
        return result;
    }

    public double getTriggerThreshold() {
        return triggerThreshold;
    }

    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }

    private Message extractSystemMessage(List<Message> messages) {
        return messages.stream()
            .filter(m -> m.role == RoleType.SYSTEM)
            .findFirst()
            .orElse(null);
    }

    private List<Message> extractConversationMessages(List<Message> messages) {
        return messages.stream()
            .filter(m -> m.role != RoleType.SYSTEM)
            .toList();
    }

    private int calculateKeepFromIndex(List<Message> conversationMsgs) {
        var lastUserIndex = findLastUserIndex(conversationMsgs);
        if (lastUserIndex < 0) {
            LOGGER.debug("No USER message found, skip compression");
            return conversationMsgs.size();
        }

        boolean isCurrentChainActive = conversationMsgs.getLast().role != RoleType.USER;

        var minKeepFromIndex = isCurrentChainActive ? lastUserIndex : conversationMsgs.size() - 1;
        var keepFromIndex = findKeepFromIndexByTurns(conversationMsgs, lastUserIndex);

        keepFromIndex = Math.min(keepFromIndex, minKeepFromIndex);

        var keepTokens = MessageTokenCounter.countFrom(conversationMsgs, keepFromIndex);
        var threshold = (int) (maxContextTokens * triggerThreshold);

        if (keepTokens >= threshold) {
            LOGGER.info("Recent turns exceed threshold ({} >= {}), keeping only current conversation chain",
                keepTokens, threshold);
            return minKeepFromIndex;
        }

        return keepFromIndex;
    }

    private int findLastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role == RoleType.USER) {
                return i;
            }
        }
        return -1;
    }

    private Integer findKeepFromIndexByTurns(List<Message> conversationMsgs, Integer lastUserIndex) {
        var keepFromIndex = lastUserIndex;
        var turnCount = 0;

        for (int i = lastUserIndex - 1; i >= 0 && turnCount < keepRecentTurns; i--) {
            keepFromIndex = i;
            if (conversationMsgs.get(i).role == RoleType.USER) {
                turnCount++;
            }
        }

        return keepFromIndex;
    }

    private String summarize(List<Message> messagesToSummarize) {
        String content = formatMessages(messagesToSummarize);
        if (content.isBlank()) {
            return "";
        }

        int targetTokens = Math.min(MAX_SUMMARY_TOKENS, Math.max(MIN_SUMMARY_TOKENS, maxContextTokens / 10));
        int targetWords = (int) (targetTokens * 0.75);
        String prompt = String.format(Prompts.COMPRESSION_PROMPT, targetWords, content);

        String summary = callLLM(prompt);
        if (summary.isBlank()) {
            return "";
        }
        return Prompts.COMPRESSION_SUMMARY_PREFIX + summary + Prompts.COMPRESSION_SUMMARY_SUFFIX;
    }

    private String callLLM(String prompt) {
        try {
            var msgs = List.of(Message.of(RoleType.USER, prompt));
            var request = CompletionRequest.of(msgs, null, 0.3, model, "memory-compressor");
            var response = llmProvider.completion(request);

            if (response != null && response.choices != null && !response.choices.isEmpty()) {
                var choice = response.choices.getFirst();
                if (choice.message != null && choice.message.content != null) {
                    return choice.message.content.trim();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to call LLM for compression", e);
        }
        return "";
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder(1024);
        for (Message msg : messages) {
            if (msg.role == RoleType.SYSTEM) {
                continue;
            }

            if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                String toolNames = msg.toolCalls.stream()
                    .map(tc -> tc.function != null ? tc.function.name : "unknown")
                    .collect(java.util.stream.Collectors.joining(", "));
                sb.append("Assistant: [Called tools: ").append(toolNames).append("]\n");
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

    public String compressToolResult(String toolName, String result, String sessionId) {
        if (result == null || result.isEmpty()) {
            return result;
        }

        int tokenCount = Tokenizer.tokenCount(result);

        if (tokenCount <= MAX_TOOL_RESULT_TOKENS) {
            return result;
        }

        try {
            Path filePath = writeToolResultToFile(toolName, result, sessionId);
            String summary = buildToolResultSummary(toolName, result, tokenCount, filePath);

            LOGGER.info("Long tool result from {} saved to file: {} ({} tokens, max: {})",
                toolName, filePath, tokenCount, MAX_TOOL_RESULT_TOKENS);

            return summary;
        } catch (IOException e) {
            LOGGER.error("Failed to write long tool result to file, keeping original", e);
            return result;
        }
    }

    public boolean shouldCompressToolResult(String result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        int tokenCount = Tokenizer.tokenCount(result);
        return tokenCount > MAX_TOOL_RESULT_TOKENS;
    }

    private Path writeToolResultToFile(String toolName, String content, String sessionId) throws IOException {
        String sid = sessionId != null ? sessionId : "default";
        Path baseTempDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        Path sessionDir = baseTempDir.resolve(sid);

        if (!Files.exists(sessionDir)) {
            Files.createDirectories(sessionDir);
        }

        String fileName = String.format("%s_%d.txt", sanitizeFileName(toolName), Instant.now().toEpochMilli());
        Path filePath = sessionDir.resolve(fileName);
        Files.writeString(filePath, content);
        return filePath;
    }

    private String buildToolResultSummary(String toolName, String content, int tokenCount, Path filePath) {
        String headContent = truncateToTokens(content, HEAD_TOKENS);
        String tailContent = extractTailTokens(content, TAIL_TOKENS);

        return String.format("""
            [Tool result truncated - full content saved to file]
            Tool: %s
            File: %s
            Total: %d tokens (exceeds %d token limit)

            === HEAD (first %d tokens) ===
            %s

            === ... truncated ... ===

            === TAIL (last %d tokens) ===
            %s

            [WARNING: This is a large file. Do NOT read the full file directly as it will be truncated again.
            Use read_file with offset and limit parameters to read specific sections, e.g.:
            read_file(file_path="%s", offset=<line_number>, limit=<num_lines>)]""",
            toolName, filePath, tokenCount, MAX_TOOL_RESULT_TOKENS,
            HEAD_TOKENS, headContent,
            TAIL_TOKENS, tailContent,
            filePath);
    }

    private String truncateToTokens(String content, int maxTokens) {
        List<Integer> tokens = Tokenizer.encode(content);
        if (tokens.size() <= maxTokens) {
            return content;
        }
        return Tokenizer.decode(tokens.subList(0, maxTokens));
    }

    private String extractTailTokens(String content, int tailTokens) {
        List<Integer> tokens = Tokenizer.encode(content);
        if (tokens.size() <= tailTokens) {
            return content;
        }
        int startIndex = tokens.size() - tailTokens;
        return Tokenizer.decode(tokens.subList(startIndex, tokens.size()));
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }
}
