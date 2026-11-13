package ai.core.media.audio;

import java.util.List;

/**
 * @author stephen
 */
public record AsrResult(String text, List<WordTimestamp> words, List<AsrPhrase> phrases) {
    public AsrResult {
        if (text == null) text = "";
        if (words == null) words = List.of();
        if (phrases == null) phrases = List.of();
    }

    /** Compatibility constructor for providers that only return a flat word list. */
    public AsrResult(String text, List<WordTimestamp> words) {
        this(text, words, List.of());
    }
}
