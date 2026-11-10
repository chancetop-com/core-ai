package ai.core.decision;

import java.util.Locale;

/**
 * The typed questions a System One decision model answers.
 *
 * @author stephen
 */
public enum DecisionType {
    NOUL("noul"),
    CHOICE("choice"),
    SCORE("score");

    private final String value;

    DecisionType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DecisionType from(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "noul", "predicate", "boolean" -> NOUL;
            case "choice" -> CHOICE;
            case "score" -> SCORE;
            default -> null;
        };
    }
}
