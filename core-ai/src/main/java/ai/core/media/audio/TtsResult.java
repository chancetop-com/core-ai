package ai.core.media.audio;

import java.util.List;

/**
 * Word timestamps may be empty depending on the provider transport (Azure REST TTS has no word
 * boundary events — the pipeline then transcribes the synthesized audio to get them, keeping one
 * consistent timestamp source).
 *
 * @author stephen
 */
public record TtsResult(byte[] audio, String contentType, List<WordTimestamp> words) {
}
