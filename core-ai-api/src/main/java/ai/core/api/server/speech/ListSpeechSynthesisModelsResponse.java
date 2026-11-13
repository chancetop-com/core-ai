package ai.core.api.server.speech;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListSpeechSynthesisModelsResponse {
    @Property(name = "models")
    public List<SpeechModelView> models;

    /** Model id a synthesis request without {@code model} resolves to; null when TTS is not configured. */
    @Property(name = "defaultModel")
    public String defaultModel;

    /** True when the server's Azure Speech credential is usable as the {@code azure-speech} fallback. */
    @Property(name = "azureConfigured")
    public Boolean azureConfigured;
}
