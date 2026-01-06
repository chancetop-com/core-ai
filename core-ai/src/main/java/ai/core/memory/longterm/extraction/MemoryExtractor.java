package ai.core.memory.longterm.extraction;

import ai.core.llm.domain.Message;
import ai.core.memory.longterm.MemoryRecord;

import java.util.List;

/**
 * @author xander
 */
public interface MemoryExtractor {

    List<MemoryRecord> extract(List<Message> messages);

    default List<MemoryRecord> extractSingle(Message message) {
        return extract(List.of(message));
    }
}
