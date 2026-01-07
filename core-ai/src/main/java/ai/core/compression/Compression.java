package ai.core.compression;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
public class Compression {
    private static final Logger LOGGER = LoggerFactory.getLogger(Compression.class);

    private static final double DEFAULT_TRIGGER_THRESHOLD = 0.8;
    private static final int DEFAULT_KEEP_RECENT_TURNS = 5;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 128000;
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
        if (model != null) {
            int modelMax = LLMModelContextRegistry.getInstance().getMaxInputTokens(model);
            this.maxContextTokens = modelMax > 0 ? modelMax : DEFAULT_MAX_CONTEXT_TOKENS;
        } else {
            this.maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;
        }
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
}
