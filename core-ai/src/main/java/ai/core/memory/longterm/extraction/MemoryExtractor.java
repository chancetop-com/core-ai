package ai.core.memory.longterm.extraction;

import ai.core.llm.domain.Message;
import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryScope;

import java.util.List;

/**
 * @author xander
 */
public interface MemoryExtractor {

    List<MemoryRecord> extract(MemoryScope scope, List<Message> messages);

    default List<MemoryRecord> extractSingle(MemoryScope scope, Message message) {
        return extract(scope, List.of(message));
    }
}
