package ai.core.agent;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import core.framework.util.Lists;

import java.util.List;

/**
 * Holds the agent's working message list (LLM context) and the append-only display history.
 * History only keeps user/assistant text — tool calls, tool results, reasoning and system
 * messages never enter it, so the session title (first user message) survives compression.
 *
 * @author stephen
 */
final class NodeMessages {
    private final List<Message> messages = Lists.newArrayList();
    private final List<Message> history = Lists.newArrayList();

    List<Message> messages() {
        return messages;
    }

    List<Message> history() {
        return history;
    }

    void add(Message message) {
        messages.add(message);
    }

    void addAll(List<Message> messages) {
        this.messages.addAll(messages);
    }

    void addToFront(Message message) {
        messages.add(0, message);
    }

    void remove(Message message) {
        messages.remove(message);
        history.remove(message);
    }

    void clear() {
        messages.clear();
        history.clear();
    }

    void restore(List<Message> messages, List<Message> history) {
        this.messages.clear();
        this.messages.addAll(messages == null ? List.of() : messages);
        this.history.clear();
        if (history != null) {
            this.history.addAll(history);
        } else {
            // legacy file without history: fall back to the user/assistant text baseline
            for (var message : this.messages) {
                recordHistory(message);
            }
        }
    }

    boolean hasUserMessage() {
        return messages.stream().anyMatch(m -> m.role == RoleType.USER && !AgentInterruptionHandler.isInterruptionMarker(m));
    }

    void recordHistory(Message message) {
        if (message.role != RoleType.USER && message.role != RoleType.ASSISTANT) return;
        var text = message.getTextContent();
        if (text == null || text.isBlank()) return;
        history.add(message);
    }
}
