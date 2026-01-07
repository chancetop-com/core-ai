package ai.core.memory.history;

import java.util.List;

/**
 * @author xander
 */
@FunctionalInterface
public interface ChatHistoryProvider {

    List<ChatRecord> loadForExtraction(String userId);
}
