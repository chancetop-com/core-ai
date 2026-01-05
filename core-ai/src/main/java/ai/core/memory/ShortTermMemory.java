package ai.core.memory;

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
//todo add a interface
public class ShortTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemory.class);

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

    public ShortTermMemory(LLMProvider llmProvider, String model) {
        this(DEFAULT_TRIGGER_THRESHOLD, DEFAULT_KEEP_RECENT_TURNS, llmProvider, model);
    }

    public ShortTermMemory(double triggerThreshold, int keepRecentTurns, LLMProvider llmProvider, String model) {
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

        var systemMsg = extractSystemMessage(messages);
        var conversationMsgs = extractConversationMessages(messages);

        if (conversationMsgs.size() <= keepRecentTurns * 2) {
            LOGGER.debug("Not enough messages to compress, keeping all");
            return messages;
        }

        int keepFromIndex = calculateKeepFromIndex(conversationMsgs);
        //todo keepFromIndex need be consider if text is Beyond context window.
        var toCompress = new ArrayList<Message>(conversationMsgs.subList(0, keepFromIndex));
        var toKeep = new ArrayList<Message>(conversationMsgs.subList(keepFromIndex, conversationMsgs.size()));

        var summary = summarize(toCompress);
        if (summary.isBlank()) {
            LOGGER.warn("Summarization returned empty result, keeping original messages");
            return messages;
        }

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

        var newTokens = MessageTokenCounter.count(result);
        LOGGER.info("Compression complete: {} -> {} tokens, {} -> {} messages",
            currentTokens, newTokens, messages.size(), result.size());

        return result;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
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
        int turnCount = 0;
        int keepFromIndex = conversationMsgs.size();

        for (int i = conversationMsgs.size() - 1; i >= 0; i--) {
            Message msg = conversationMsgs.get(i);
            if (msg.role == RoleType.USER) {
                turnCount++;
                if (turnCount >= keepRecentTurns) {
                    keepFromIndex = i;
                    break;
                }
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
        String prompt = String.format(Prompts.SHORT_TERM_MEMORY_COMPRESS_PROMPT, targetWords, content);

        String summary = callLLM(prompt);
        if (summary.isBlank()) {
            return "";
        }
        return Prompts.SHORT_TERM_MEMORY_SUMMARY_PREFIX + summary + Prompts.SHORT_TERM_MEMORY_SUMMARY_SUFFIX;
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
}
