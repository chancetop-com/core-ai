package ai.core.media.audio;

/**
 * Clip-relative transcript span in seconds. Providers that only return text leave the segment list empty.
 *
 * @author stephen
 */
public record TranscriptSegment(String text, double startSec, double endSec) {
}
