package ai.core.memory;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Short-term memory compression service for Agent.
 * Provides synchronous conversation compression when context exceeds threshold.
 *
 * <p>Compression strategy:
 * <ul>
 *   <li>Triggered when token count exceeds threshold ratio of max context</li>
 *   <li>Keeps system message and recent N turns intact</li>
 *   <li>Compresses older messages into a summary message</li>
 * </ul>
 *
 * @author xander
 */
public class ShortTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortTermMemory.class);

    private static final double DEFAULT_TRIGGER_THRESHOLD = 0.7;
    private static final int DEFAULT_KEEP_RECENT_TURNS = 5;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 128000;
    private static final int MIN_SUMMARY_TOKENS = 500;
    private static final int MAX_SUMMARY_TOKENS = 4000;

    private static final String COMPRESS_PROMPT = """
        Summarize the following conversation into a concise summary.
        Requirements:
        1. Preserve key facts, decisions, and context
        2. Keep important user preferences and goals mentioned
        3. Remove redundant back-and-forth and filler content
        4. Use bullet points for clarity
        5. Keep within %d words

        Conversation to summarize:
        %s

        Output summary directly:
        """;

    private final double triggerThreshold;
    private final int keepRecentTurns;
    private int maxContextTokens;

    private LLMProvider llmProvider;
    private String model;
    private String lastSummary = "";

    public ShortTermMemory() {
        this(DEFAULT_TRIGGER_THRESHOLD, DEFAULT_KEEP_RECENT_TURNS);
    }

    public ShortTermMemory(double triggerThreshold, int keepRecentTurns) {
        this.triggerThreshold = triggerThreshold;
        this.keepRecentTurns = keepRecentTurns;
        this.maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;
    }

    public void setLLMProvider(LLMProvider llmProvider, String model) {
        this.llmProvider = llmProvider;
        this.model = model;
        if (model != null) {
            int modelMax = LLMModelContextRegistry.getInstance().getMaxInputTokens(model);
            if (modelMax > 0) {
                this.maxContextTokens = modelMax;
            }
        }
    }

    /**
     * Check if compression should be triggered based on current token count.
     */
    public boolean shouldCompress(int currentTokens) {
        if (llmProvider == null) {
            return false;
        }
        return currentTokens >= (int) (maxContextTokens * triggerThreshold);
    }

    /**
     * Compress messages by summarizing older messages and keeping recent ones.
     * Returns a new message list with compression applied.
     *
     * @param messages the current message list
     * @return compressed message list, or original if no compression needed
     */
    public List<Message> compress(List<Message> messages) {
        if (messages == null || messages.isEmpty() || llmProvider == null) {
            return messages;
        }

        int currentTokens = MessageTokenCounter.count(messages);
        if (!shouldCompress(currentTokens)) {
            return messages;
        }

        LOGGER.info("Compressing messages: currentTokens={}, threshold={}", currentTokens, (int) (maxContextTokens * triggerThreshold));

        Message systemMsg = extractSystemMessage(messages);
        List<Message> conversationMsgs = extractConversationMessages(messages);

        if (conversationMsgs.size() <= keepRecentTurns * 2) {
            LOGGER.debug("Not enough messages to compress, keeping all");
            return messages;
        }

        int keepFromIndex = calculateKeepFromIndex(conversationMsgs);
        List<Message> toCompress = new ArrayList<>(conversationMsgs.subList(0, keepFromIndex));
        List<Message> toKeep = new ArrayList<>(conversationMsgs.subList(keepFromIndex, conversationMsgs.size()));

        String summary = summarize(toCompress);
        if (summary.isBlank()) {
            LOGGER.warn("Summarization returned empty result, keeping original messages");
            return messages;
        }

        lastSummary = summary;
        Message summaryMsg = Message.of(RoleType.USER, "[Conversation Summary]\n" + summary);

        List<Message> result = new ArrayList<>();
        if (systemMsg != null) {
            result.add(systemMsg);
        }
        result.add(summaryMsg);
        result.add(Message.of(RoleType.ASSISTANT, "I understand. I'll continue our conversation with this context in mind."));
        result.addAll(toKeep);

        int newTokens = MessageTokenCounter.count(result);
        LOGGER.info("Compression complete: {} -> {} tokens, {} -> {} messages",
            currentTokens, newTokens, messages.size(), result.size());

        return result;
    }

    /**
     * Get the last generated summary.
     */
    public String getLastSummary() {
        return lastSummary;
    }

    public void clear() {
        this.lastSummary = "";
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

        return Math.max(0, keepFromIndex);
    }

    private String summarize(List<Message> messagesToSummarize) {
        String content = formatMessages(messagesToSummarize);
        if (content.isBlank()) {
            return "";
        }

        int targetTokens = Math.min(MAX_SUMMARY_TOKENS, Math.max(MIN_SUMMARY_TOKENS, maxContextTokens / 10));
        int targetWords = (int) (targetTokens * 0.75);
        String prompt = String.format(COMPRESS_PROMPT, targetWords, content);

        return callLLM(prompt);
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
