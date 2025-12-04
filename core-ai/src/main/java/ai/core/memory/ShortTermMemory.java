package ai.core.memory;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Short-term memory for managing recent conversation context.
 * Implements a sliding window with optional rolling summary for evicted messages.
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Sliding window based on message count and token limit</li>
 *   <li>Automatic eviction of old messages</li>
 *   <li>Rolling summary tracking for evicted content</li>
 *   <li>Model-aware token limit auto-detection</li>
 * </ul>
 *
 * @author xander
 */
public class ShortTermMemory implements MemoryStore {
    private static final int DEFAULT_MAX_TOKENS = 4000;
    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final double DEFAULT_TOKEN_RATIO = 0.8;

    private final Deque<Message> messages;
    private final StringBuilder evictedContent;
    private final int maxMessages;
    private final int maxTokens;
    private final boolean enableRollingSummary;
    private String rollingSummary;

    /**
     * Create with default settings.
     */
    public ShortTermMemory() {
        this(DEFAULT_MAX_MESSAGES, DEFAULT_MAX_TOKENS, true);
    }

    /**
     * Create with explicit limits.
     *
     * @param maxMessages         maximum number of messages
     * @param maxTokens           maximum token count
     * @param enableRollingSummary whether to track evicted content
     */
    public ShortTermMemory(int maxMessages, int maxTokens, boolean enableRollingSummary) {
        this.messages = new ArrayDeque<>();
        this.evictedContent = new StringBuilder(512);
        this.rollingSummary = "";
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.enableRollingSummary = enableRollingSummary;
    }

    /**
     * Create with model-based auto token limit detection.
     *
     * @param maxMessages maximum number of messages
     * @param modelName   model name for context window lookup
     */
    public ShortTermMemory(int maxMessages, String modelName) {
        this(maxMessages, calculateMaxTokens(modelName), true);
    }

    private static int calculateMaxTokens(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return DEFAULT_MAX_TOKENS;
        }
        int contextWindow = LLMModelContextRegistry.getInstance().getContextWindow(modelName);
        return (int) (contextWindow * DEFAULT_TOKEN_RATIO);
    }

    // ==================== Core Operations ====================

    @Override
    public void add(String content) {
        add(Message.of(RoleType.USER, content));
    }

    @Override
    public void add(Message message) {
        messages.addLast(message);
        evictIfNecessary();
    }

    /**
     * Add multiple messages.
     *
     * @param newMessages messages to add
     */
    public void addAll(List<Message> newMessages) {
        for (Message msg : newMessages) {
            messages.addLast(msg);
        }
        evictIfNecessary();
    }

    @Override
    public List<String> retrieve(String query, int topK) {
        // Short-term memory returns recent messages (no semantic search)
        List<Message> recent = getRecentMessages(topK);
        return recent.stream()
            .map(m -> formatMessage(m))
            .toList();
    }

    @Override
    public String buildContext() {
        StringBuilder sb = new StringBuilder(1024);

        if (!rollingSummary.isEmpty()) {
            sb.append("[Previous Context]\n");
            sb.append(rollingSummary);
            sb.append("\n\n");
        }

        sb.append("[Recent Conversation]\n");
        for (Message msg : messages) {
            if (msg.role != RoleType.SYSTEM) {
                sb.append(formatMessage(msg)).append('\n');
            }
        }

        return sb.toString();
    }

    @Override
    public void clear() {
        messages.clear();
        evictedContent.setLength(0);
        rollingSummary = "";
    }

    @Override
    public int size() {
        return messages.size();
    }

    // ==================== Message Access ====================

    /**
     * Get all messages (defensive copy).
     */
    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * Get the most recent N messages.
     */
    public List<Message> getRecentMessages(int n) {
        List<Message> all = new ArrayList<>(messages);
        if (all.size() <= n) {
            return all;
        }
        return all.subList(all.size() - n, all.size());
    }

    /**
     * Get the last user-assistant exchange.
     */
    public String getLatestExchange() {
        List<Message> all = new ArrayList<>(messages);
        StringBuilder sb = new StringBuilder(256);

        Message lastUser = null;
        Message lastAssistant = null;

        for (int i = all.size() - 1; i >= 0; i--) {
            Message msg = all.get(i);
            if (msg.role == RoleType.ASSISTANT && lastAssistant == null) {
                lastAssistant = msg;
            } else if (msg.role == RoleType.USER && lastUser == null) {
                lastUser = msg;
            }
            if (lastUser != null && lastAssistant != null) {
                break;
            }
        }

        if (lastUser != null) {
            sb.append("User: ").append(lastUser.content);
        }
        if (lastAssistant != null) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("Assistant: ").append(lastAssistant.content);
        }

        return sb.toString();
    }

    // ==================== Rolling Summary ====================

    /**
     * Get evicted content (for external summarization).
     */
    public String getEvictedContent() {
        return evictedContent.toString();
    }

    /**
     * Check if there is evicted content pending summarization.
     */
    public boolean hasEvictedContent() {
        return !evictedContent.isEmpty();
    }

    /**
     * Clear evicted content after summarization.
     */
    public void clearEvictedContent() {
        evictedContent.setLength(0);
    }

    /**
     * Get the rolling summary.
     */
    public String getRollingSummary() {
        return rollingSummary;
    }

    /**
     * Set the rolling summary.
     */
    public void setRollingSummary(String summary) {
        this.rollingSummary = summary != null ? summary : "";
    }

    /**
     * Append to rolling summary.
     */
    public void appendRollingSummary(String additionalSummary) {
        if (additionalSummary == null || additionalSummary.isEmpty()) return;
        if (rollingSummary.isEmpty()) {
            rollingSummary = additionalSummary;
        } else {
            rollingSummary = rollingSummary + "\n\n" + additionalSummary;
        }
    }

    // ==================== Metrics ====================

    /**
     * Get current token count.
     */
    public int getTokenCount() {
        return calculateTokenCount();
    }

    /**
     * Get max token limit.
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Get max message limit.
     */
    public int getMaxMessages() {
        return maxMessages;
    }

    // ==================== Internal ====================

    private void evictIfNecessary() {
        while (messages.size() > maxMessages) {
            evictOldest();
        }
        while (calculateTokenCount() > maxTokens && messages.size() > 1) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Message evicted = messages.pollFirst();
        if (evicted != null && enableRollingSummary && evicted.role != RoleType.SYSTEM) {
            if (!evictedContent.isEmpty()) evictedContent.append('\n');
            evictedContent.append(formatMessage(evicted));
        }
    }

    private int calculateTokenCount() {
        return MessageTokenCounter.count(new ArrayList<>(messages));
    }

    private String formatMessage(Message message) {
        String role = switch (message.role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case TOOL -> "Tool";
            case SYSTEM -> "System";
        };
        return role + ": " + (message.content != null ? message.content : "");
    }
}
