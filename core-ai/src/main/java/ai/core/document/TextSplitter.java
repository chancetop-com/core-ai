package ai.core.document;

import java.util.List;

/**
 * @author stephen
 */
public interface TextSplitter {
    List<TextChunk> split(String text);
}
