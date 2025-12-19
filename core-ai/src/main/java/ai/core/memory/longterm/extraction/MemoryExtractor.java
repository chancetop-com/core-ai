package ai.core.memory.longterm.extraction;

import ai.core.llm.domain.Message;
import ai.core.memory.longterm.MemoryRecord;

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
     * @param userId   the user ID
     * @param messages the conversation messages to extract from
     * @return extracted memory records (without embeddings)
     */
    List<MemoryRecord> extract(String userId, List<Message> messages);

    /**
     * Extract memories from a single message.
     *
     * @param userId  the user ID
     * @param message the message to extract from
     * @return extracted memory records (without embeddings)
     */
    default List<MemoryRecord> extractSingle(String userId, Message message) {
        return extract(userId, List.of(message));
    }
}
