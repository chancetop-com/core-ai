package ai.core.memory;

import ai.core.document.Tokenizer;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Short-term memory for managing recent conversation context.
 * Implements a sliding window with optional rolling summary for evicted messages.
 *
 * @author stephen
 */
public class ShortTermMemory {
    private final ShortTermMemoryConfig config;
    private final Deque<Message> messages;
    private final Map<String, Object> workingContext;
    private final StringBuilder evictedContent;
    private String rollingSummary;

    public ShortTermMemory() {
        this(ShortTermMemoryConfig.defaultConfig());
    }

    public ShortTermMemory(ShortTermMemoryConfig config) {
        this.config = config;
        this.messages = new ArrayDeque<>();
        this.workingContext = new HashMap<>();
        this.evictedContent = new StringBuilder(512);
        this.rollingSummary = "";
    }

    /**
     * Add a message to short-term memory.
     * Automatically evicts old messages if limits are exceeded.
     *
     * @param message the message to add
     */
    public void addMessage(Message message) {
        messages.addLast(message);
        evictIfNecessary();
    }

    /**
     * Add multiple messages to short-term memory.
     *
     * @param newMessages the messages to add
     */
    public void addMessages(List<Message> newMessages) {
        for (Message message : newMessages) {
            messages.addLast(message);
        }
        evictIfNecessary();
    }

    private void evictIfNecessary() {
        // Evict by message count
        while (messages.size() > config.getMaxMessages()) {
            evictOldest();
        }

        // Evict by token count
        while (calculateTokenCount() > config.getMaxTokens() && messages.size() > 1) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Message evicted = messages.pollFirst();
        if (evicted != null && config.isEnableRollingSummary()) {
            appendToEvicted(evicted);
        }
    }

    private void appendToEvicted(Message message) {
        if (message.role == RoleType.SYSTEM) {
            return;
        }
        if (!evictedContent.isEmpty()) {
            evictedContent.append('\n');
        }
        evictedContent.append(formatMessage(message));
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

    private int calculateTokenCount() {
        int tokens = 0;
        for (Message msg : messages) {
            if (msg.content != null) {
                tokens += Tokenizer.tokenCount(msg.content);
            }
            if (msg.toolCalls != null) {
                for (var call : msg.toolCalls) {
                    if (call.function != null && call.function.arguments != null) {
                        tokens += Tokenizer.tokenCount(call.function.arguments);
                    }
                }
            }
        }
        return tokens;
    }

    /**
     * Get all messages in short-term memory.
     *
     * @return list of messages (defensive copy)
     */
    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * Get the most recent N messages.
     *
     * @param n number of messages to retrieve
     * @return list of recent messages
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
     *
     * @return formatted string of the latest exchange
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
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("Assistant: ").append(lastAssistant.content);
        }

        return sb.toString();
    }

    /**
     * Get the content of evicted messages (for summary generation).
     *
     * @return evicted message content
     */
    public String getEvictedContent() {
        return evictedContent.toString();
    }

    /**
     * Check if there is evicted content that needs summarization.
     *
     * @return true if there is evicted content
     */
    public boolean hasEvictedContent() {
        return !evictedContent.isEmpty();
    }

    /**
     * Clear evicted content after summary is generated.
     */
    public void clearEvictedContent() {
        evictedContent.setLength(0);
    }

    /**
     * Get the rolling summary of evicted messages.
     *
     * @return the rolling summary
     */
    public String getRollingSummary() {
        return rollingSummary;
    }

    /**
     * Update the rolling summary.
     *
     * @param summary the new summary
     */
    public void setRollingSummary(String summary) {
        this.rollingSummary = summary != null ? summary : "";
    }

    /**
     * Append to existing rolling summary.
     *
     * @param additionalSummary summary to append
     */
    public void appendRollingSummary(String additionalSummary) {
        if (additionalSummary == null || additionalSummary.isEmpty()) {
            return;
        }
        if (rollingSummary.isEmpty()) {
            rollingSummary = additionalSummary;
        } else {
            rollingSummary = rollingSummary + "\n\n" + additionalSummary;
        }
    }

    /**
     * Build context string including rolling summary and recent messages.
     *
     * @return formatted context string
     */
    public String buildContext() {
        StringBuilder sb = new StringBuilder(1024);

        if (!rollingSummary.isEmpty()) {
            sb.append("[Previous Context Summary]\n");
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

    // Working context methods

    /**
     * Store a value in working context.
     *
     * @param key   the key
     * @param value the value
     */
    public void put(String key, Object value) {
        workingContext.put(key, value);
    }

    /**
     * Get a value from working context.
     *
     * @param key the key
     * @return the value or null
     */
    public Object get(String key) {
        return workingContext.get(key);
    }

    /**
     * Get a typed value from working context.
     *
     * @param key  the key
     * @param type the expected type
     * @param <T>  the type parameter
     * @return the value or null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = workingContext.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Check if working context contains a key.
     *
     * @param key the key
     * @return true if present
     */
    public boolean has(String key) {
        return workingContext.containsKey(key);
    }

    /**
     * Remove a value from working context.
     *
     * @param key the key
     */
    public void remove(String key) {
        workingContext.remove(key);
    }

    /**
     * Get all working context entries.
     *
     * @return copy of working context map
     */
    public Map<String, Object> getWorkingContext() {
        return new HashMap<>(workingContext);
    }

    /**
     * Clear working context.
     */
    public void clearWorkingContext() {
        workingContext.clear();
    }

    // Utility methods

    /**
     * Get the number of messages.
     *
     * @return message count
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * Get current token count.
     *
     * @return token count
     */
    public int getTokenCount() {
        return calculateTokenCount();
    }

    /**
     * Check if memory is empty.
     *
     * @return true if no messages
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * Clear all messages and context.
     */
    public void clear() {
        messages.clear();
        workingContext.clear();
        evictedContent.setLength(0);
        rollingSummary = "";
    }

    /**
     * Clear only messages, keeping working context.
     */
    public void clearMessages() {
        messages.clear();
        evictedContent.setLength(0);
    }

    public ShortTermMemoryConfig getConfig() {
        return config;
    }
}
