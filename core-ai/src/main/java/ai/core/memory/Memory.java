package ai.core.memory;

import ai.core.document.Document;
import ai.core.llm.domain.Message;

import java.util.List;

/**
 * Base class for memory implementations.
 *
 * @author stephen
 */
public abstract class Memory {
    public static final String PROMPT_MEMORY_TEMPLATE = "\n\n### Memory\n";

    public abstract void extractAndSave(List<Message> conversation);

    public abstract List<Document> retrieve(String query);

    public abstract void add(String text);

    public abstract void clear();

    public abstract List<Document> list();
}
