package ai.core.agent;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import core.framework.util.Lists;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Holds the agent's working message list (LLM context) and the append-only display history.
 * History only keeps user/assistant text — tool calls, tool results, reasoning and system
 * messages never enter it, so the session title (first user message) survives compression.
 * <p>
 * A history entry is a text snapshot, never the original {@link Message}: sharing the object used to
 * pin every inline image of the conversation for the whole session, which is exactly the retention
 * the session budget cannot reclaim. Undo therefore matches by role + text from the tail.
 *
 * @author stephen
 */
final class NodeMessages {
    // legacy snapshots may carry an inline data URI in a text part; a loaded history must never keep it
    private static final Pattern LEGACY_INLINE_MEDIA = Pattern.compile("data:[\\w.+-]+/[\\w.+-]+;base64,[A-Za-z0-9+/=\\s]+");
    private static final String OMITTED_MEDIA = "[inline image omitted]";

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
        removeHistory(message);
    }

    /**
     * Removes the newest history entry matching the message's role and text. History entries are
     * snapshots, so object identity cannot be used (undo receives the working messages).
     */
    void removeHistory(Message message) {
        if (message == null) return;
        var text = sanitize(message.getTextContent());
        if (text == null) return;
        for (var i = history.size() - 1; i >= 0; i--) {
            var entry = history.get(i);
            if (entry.role == message.role && text.equals(entry.getTextContent())) {
                history.remove(i);
                return;
            }
        }
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
            // rebuild snapshots so a legacy file that stored the original messages (images included)
            // does not keep pinning them after load
            for (var message : history) {
                recordHistory(message);
            }
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
        if (message == null) return;
        if (message.role != RoleType.USER && message.role != RoleType.ASSISTANT) return;
        var text = sanitize(message.getTextContent());
        if (text == null || text.isBlank()) return;
        history.add(Message.of(message.role, text));
    }

    private static String sanitize(String text) {
        if (text == null || !text.contains("data:")) return text;
        return LEGACY_INLINE_MEDIA.matcher(text).replaceAll(OMITTED_MEDIA);
    }
}
