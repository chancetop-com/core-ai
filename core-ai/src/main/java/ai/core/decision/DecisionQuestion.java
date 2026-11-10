package ai.core.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One typed question in a decision request. {@code criteria} carries the labels and descriptions of a
 * {@code noul} or {@code choice} question; {@code levels} carries the ordered scale (low to high) of a
 * {@code score} question.
 *
 * @author stephen
 */
public record DecisionQuestion(String name, DecisionType type, String instructions,
                               Map<String, String> criteria, List<String> levels) {
    public DecisionQuestion {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("decision question name is required");
        if (type == null) throw new IllegalArgumentException("decision question type is required");
        if (type == DecisionType.CHOICE && (criteria == null || criteria.isEmpty())) {
            throw new IllegalArgumentException("choice question requires criteria: " + name);
        }
        if (type == DecisionType.SCORE && (levels == null || levels.isEmpty())) {
            throw new IllegalArgumentException("score question requires ordered levels: " + name);
        }
        criteria = criteria == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
        levels = levels == null ? null : List.copyOf(levels);
    }

    public static DecisionQuestion noul(String name, String instructions, Map<String, String> criteria) {
        return new DecisionQuestion(name, DecisionType.NOUL, instructions, criteria, null);
    }

    public static DecisionQuestion choice(String name, String instructions, Map<String, String> criteria) {
        return new DecisionQuestion(name, DecisionType.CHOICE, instructions, criteria, null);
    }

    public static DecisionQuestion score(String name, String instructions, List<String> levels) {
        return new DecisionQuestion(name, DecisionType.SCORE, instructions, null, levels);
    }
}
