package ai.core.llm;

/**
 * @author Xander
 */
@FunctionalInterface
public interface ModelModalityRegistry {
    ModalitySupport supports(String model, InputModality modality);
}
