package ai.core.media.audio;

import java.util.List;

/**
 * Provider-normalized transcription output. {@code language} and {@code durationSeconds} are optional:
 * plain json responses carry only the text, while verbose/word-timestamp providers fill the rest.
 *
 * @author stephen
 */
public record TranscriptionResult(String text, String language, Double durationSeconds, List<TranscriptSegment> segments) {
    public TranscriptionResult {
        if (text == null) text = "";
        if (segments == null) segments = List.of();
    }

    public TranscriptionResult(String text) {
        this(text, null, null, List.of());
    }
}
