package ai.core.session.transcript;

import java.util.function.Supplier;

/**
 * Host-supplied facts that belong to every record but are not events.
 *
 * @author stephen
 */
public record TranscriptMetadata(String workspace, String engineVersion, String client,
                                 Supplier<String> model, Supplier<String> thinkingLevel) {
    public String currentModel() {
        return model == null ? null : model.get();
    }

    public String currentThinkingLevel() {
        return thinkingLevel == null ? null : thinkingLevel.get();
    }
}
