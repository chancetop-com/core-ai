package ai.core.media.audio;

/**
 * @author stephen
 */
public record AsrSpec(byte[] audio, String contentType, String locale, Integer maxSpeakers) {
    public AsrSpec {
        if (audio == null) audio = new byte[0];
        if (maxSpeakers == null || maxSpeakers < 1) maxSpeakers = 8;
    }

    public AsrSpec(byte[] audio, String contentType, String locale) {
        this(audio, contentType, locale, 8);
    }
}
