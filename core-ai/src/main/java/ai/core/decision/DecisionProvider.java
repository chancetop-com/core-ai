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

    default String name() {
        return getClass().getSimpleName();
    }
}
