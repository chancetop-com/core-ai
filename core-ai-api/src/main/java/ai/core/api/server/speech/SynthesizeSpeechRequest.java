package ai.core.api.server.speech;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SynthesizeSpeechRequest {
    @Property(name = "text")
    public String text;

    @Property(name = "model")
    public String model;

    @Property(name = "voice")
    public String voice;

    @Property(name = "language")
    public String language;

    @Property(name = "format")
    public String format;

    @Property(name = "speed")
    public Double speed;

    @Property(name = "instructions")
    public String instructions;
}
