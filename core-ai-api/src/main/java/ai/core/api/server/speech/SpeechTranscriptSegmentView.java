package ai.core.api.server.speech;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SpeechTranscriptSegmentView {
    @Property(name = "text")
    public String text;

    /** Diarization label (Azure numeric, OpenAI letter); null when the provider does not separate speakers. */
    @Property(name = "speaker")
    public String speaker;

    @Property(name = "start")
    public Double start;

    @Property(name = "end")
    public Double end;
}
