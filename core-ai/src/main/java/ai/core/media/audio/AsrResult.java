package ai.core.media.audio;

import java.util.List;

/**
 * @author stephen
 */
public record AsrResult(String text, List<WordTimestamp> words) {
}
