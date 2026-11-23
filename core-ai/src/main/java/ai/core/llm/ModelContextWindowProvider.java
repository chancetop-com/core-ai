package ai.core.llm;

/**
 * Supplies a model's max input tokens when the bundled model registry has no entry for it — typically a live
 * gateway catalog, which knows models that ship after the client binary.
 *
 * @author stephen
 */
@FunctionalInterface
public interface ModelContextWindowProvider {
    /** @return the max input tokens, or a non-positive value when this provider cannot resolve the model */
    int contextWindow(String modelName);
}
