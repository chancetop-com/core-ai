package ai.core.memory.longterm.extraction;

import ai.core.llm.domain.Message;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.Namespace;

import java.util.List;

/**
 * Interface for extracting long-term memories from conversations.
 *
 * @author xander
 */
public interface MemoryExtractor {

    /**
     * Extract memories from a list of messages.
     *
     * @param namespace the namespace for extracted memories
     * @param messages  the conversation messages to extract from
     * @return extracted memory records (without embeddings)
     */
    List<MemoryRecord> extract(Namespace namespace, List<Message> messages);

    /**
     * Extract memories from a list of messages.
     *
     * @param userId   the user ID
     * @param messages the conversation messages to extract from
     * @return extracted memory records (without embeddings)
     * @deprecated Use {@link #extract(Namespace, List)} instead
     */
    @Deprecated
    default List<MemoryRecord> extract(String userId, List<Message> messages) {
        return extract(Namespace.forUser(userId), messages);
    }

    /**
     * Extract memories from a single message.
     *
     * @param namespace the namespace for extracted memories
     * @param message   the message to extract from
     * @return extracted memory records (without embeddings)
     */
    default List<MemoryRecord> extractSingle(Namespace namespace, Message message) {
        return extract(namespace, List.of(message));
    }
}
