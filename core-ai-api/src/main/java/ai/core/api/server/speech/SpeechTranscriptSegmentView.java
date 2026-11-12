package ai.core.api.server.speech;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SpeechTranscriptSegmentView {
    @Property(name = "text")
    public String text;

    @Property(name = "start")
    public Double start;

    @Property(name = "end")
    public Double end;
}
