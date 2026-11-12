package ai.core.api.server.speech;

import core.framework.api.json.Property;

/**
 * One speech-to-text model the caller may use. {@code modelId} is what clients pass back on a
 * transcription request; {@code upstreamModel} is only informative.
 *
 * @author stephen
 */
public class SpeechModelView {
    @Property(name = "modelId")
    public String modelId;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "upstreamModel")
    public String upstreamModel;
}
