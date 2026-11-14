package ai.core.decision;

/**
 * Decision models do not generate text: they take one state plus typed questions and return structured
 * verdicts. They must not be implemented on top of {@code LLMProvider}, whose message/SSE contract does
 * not fit this call shape.
 *
 * @author stephen
 */
public interface DecisionProvider {
    DecisionResponse decide(DecisionRequest request);

    /**
     * Deadline-aware variant. Implementations that own an HTTP transport must apply the limit there;
     * the default keeps existing providers source compatible.
     */
    default DecisionResponse decide(DecisionRequest request, DecisionOptions options) {
        return decide(request);
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
