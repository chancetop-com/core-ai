package ai.core.media.audio;

/**
 * @author stephen
 */
public record TtsSpec(String text, String voiceId, String ssml, String style, Double speed, Double pitch, String locale) {
}
