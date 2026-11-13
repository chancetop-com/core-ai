package ai.core.media.audio;

/**
 * Clip-relative transcript span in seconds. {@code speaker} is the provider's diarization label,
 * or null when the response has no speaker separation.
 *
 * @author stephen
 */
public record TranscriptSegment(String text, double startSec, double endSec, String speaker) {
    public TranscriptSegment(String text, double startSec, double endSec) {
        this(text, startSec, endSec, null);
    }
}
