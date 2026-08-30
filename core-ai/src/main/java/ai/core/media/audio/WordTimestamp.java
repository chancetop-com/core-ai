package ai.core.media.audio;

/**
 * Clip-relative word timing in seconds (the subtitles node converts to the absolute episode
 * timeline at assembly, design §7.2).
 *
 * @author stephen
 */
public record WordTimestamp(String word, double startSec, double endSec) {
}
