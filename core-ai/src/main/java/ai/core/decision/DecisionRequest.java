package ai.core.decision;

import java.util.List;

/**
 * A decision call: one state judged by one or more typed questions. {@code state} is raw text or JSON
 * text; the adapter passes JSON objects/arrays through as JSON values.
 *
 * @author stephen
 */
public record DecisionRequest(String model, String state, List<DecisionQuestion> questions) {
    public DecisionRequest {
        if (state == null) throw new IllegalArgumentException("decision state is required");
        if (questions == null || questions.isEmpty()) throw new IllegalArgumentException("decision questions are required");
        questions = List.copyOf(questions);
    }

    public DecisionRequest withModel(String model) {
        return new DecisionRequest(model, state, questions);
    }
}
