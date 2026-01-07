package ai.core.memory.longterm.extraction;

import ai.core.memory.history.ChatRecord;
import ai.core.memory.longterm.MemoryRecord;

import java.util.List;

/**
 * Extracts memory records from chat history.
 *
 * @author xander
 */
public interface MemoryExtractor {

    List<MemoryRecord> extract(List<ChatRecord> records);

    default List<MemoryRecord> extractSingle(ChatRecord record) {
        return extract(List.of(record));
    }
}
