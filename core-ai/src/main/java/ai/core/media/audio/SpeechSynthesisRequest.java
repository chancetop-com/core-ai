package ai.core.media.audio;

/**
 * One text-to-speech request after gateway routing. {@code format} is the requested audio container
 * (mp3 by default); {@code voice} is provider-specific.
 *
 * @author stephen
 */
public record SpeechSynthesisRequest(String model, String text, String voice, String language,
                                     String format, Double speed, String instructions) {
    public SpeechSynthesisRequest {
        if (text == null) text = "";
    }
}
