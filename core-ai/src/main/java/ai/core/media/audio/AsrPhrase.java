package ai.core.media.audio;

/**
 * One phrase-level transcription result. Speaker is the provider's label when diarization is
 * enabled (Azure Speech returns numeric labels, OpenAI diarization returns letters), null when the
 * provider does not separate speakers.
 *
 * @author stephen
 */
public record AsrPhrase(String text, String speaker, double startSec, double endSec) {
    public AsrPhrase {
        if (text == null) text = "";
    }
}
