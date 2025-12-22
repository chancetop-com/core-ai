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

    List<MemoryRecord> extract(Namespace namespace, List<Message> messages);

    default List<MemoryRecord> extractSingle(Namespace namespace, Message message) {
        return extract(namespace, List.of(message));
    }
}
