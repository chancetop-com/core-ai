package ai.core.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
public record DecisionResponse(Map<String, DecisionAnswer> answers, String model, DecisionUsage usage,
                               DecisionRoute route) {
    public DecisionResponse {
        answers = answers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        route = route == null ? DecisionRoute.empty() : route;
    }

    public DecisionResponse(Map<String, DecisionAnswer> answers, String model, DecisionUsage usage) {
        this(answers, model, usage, DecisionRoute.empty());
    }

    public DecisionResponse withRoute(DecisionRoute route) {
        return new DecisionResponse(answers, model, usage, route);
    }
}
