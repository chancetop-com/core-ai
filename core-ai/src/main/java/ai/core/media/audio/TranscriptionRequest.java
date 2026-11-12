package ai.core.media.audio;

/**
 * One audio clip to transcribe. {@code model} is the upstream model name after gateway routing;
 * {@code language} is the caller's language hint (BCP-47 or ISO-639-1), null/blank means auto-detect.
 *
 * @author stephen
 */
public record TranscriptionRequest(String model, byte[] audio, String contentType, String language, String prompt) {
    public TranscriptionRequest {
        if (audio == null) audio = new byte[0];
    }
}
