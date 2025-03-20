package ai.core.document;

/**
 * @author stephen
 */
public class TextChunk {
    private final String chunk;
    private final String summary;

    public TextChunk(String chunk) {
        this.chunk = chunk;
        this.summary = null;
    }

    public TextChunk(String text, String summary) {
        this.chunk = text;
        this.summary = summary;
    }

    public String chunk() {
        return chunk;
    }

    public String summary() {
        return summary;
    }

    public String embeddingText() {
        return summary == null ? chunk : summary;
    }
}
