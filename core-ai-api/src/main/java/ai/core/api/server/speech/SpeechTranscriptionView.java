package ai.core.api.server.speech;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SpeechTranscriptionView {
    @Property(name = "text")
    public String text;

    /** Requested or provider-detected language; null when the provider did not report one. */
    @Property(name = "language")
    public String language;

    @Property(name = "durationSeconds")
    public Double durationSeconds;

    @Property(name = "model")
    public String model;

    @Property(name = "provider")
    public String provider;

    @Property(name = "segments")
    public List<SpeechTranscriptSegmentView> segments;
}
