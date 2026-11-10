package ai.core.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
public record DecisionResponse(Map<String, DecisionAnswer> answers, String model, DecisionUsage usage) {
    public DecisionResponse {
        answers = answers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }
}
