package ai.core.decision;

import java.util.Map;

/**
 * Vendor-independent decision answer. {@code probability} is populated for {@code noul}, {@code choice}
 * and {@code score} for the winning label, {@code choices} for the explicit option, {@code score} for the
 * weighted level value and {@code probabilities} for the full distribution.
 *
 * @author stephen
 */
public record DecisionAnswer(String name, DecisionType type, Double probability, String choice,
                             Double score, Map<String, Double> probabilities, Double confidence,
                             Map<String, String> legend) {
}
