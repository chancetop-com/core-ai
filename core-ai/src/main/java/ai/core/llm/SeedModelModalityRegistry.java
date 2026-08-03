package ai.core.llm;

/**
 * Modality registry backed by the bundled litellm model database.
 * litellm convention: capability flags are only reliable when explicitly true,
 * an existing entry without the flag means the model does not support the modality;
 * a missing entry means we simply do not know.
 *
 * @author Xander
 */
public final class SeedModelModalityRegistry implements ModelModalityRegistry {
    public static final SeedModelModalityRegistry INSTANCE = new SeedModelModalityRegistry();

    private SeedModelModalityRegistry() {
    }

    @Override
    public ModalitySupport supports(String model, InputModality modality) {
        if (modality == InputModality.TEXT) return ModalitySupport.SUPPORTED;
        if (modality == InputModality.AUDIO) return ModalitySupport.UNKNOWN;
        var info = LLMModelContextRegistry.getInstance().getModelInfo(model);
        if (info == null) return ModalitySupport.UNKNOWN;
        var flag = switch (modality) {
            case IMAGE -> info.supportsVision();
            case FILE -> info.supportsPdfInput();
            case VIDEO -> info.supportsVideoInput();
            default -> null;
        };
        return Boolean.TRUE.equals(flag) ? ModalitySupport.SUPPORTED : ModalitySupport.UNSUPPORTED;
    }
}
