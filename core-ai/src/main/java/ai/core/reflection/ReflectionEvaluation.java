package ai.core.reflection;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Objects;

/**
 * Structure reflection result with json format
 * Improved: Structured reflection evaluation result with better validation
 *
 * @author xander
 */
public final class ReflectionEvaluation {

    public static Builder builder() {
        return new Builder();
    }

    @Property(name = "score")
    public Integer score;  // score between 1-10

    @Property(name = "pass")
    public Boolean pass;

    @Property(name = "strengths")
    public List<String> strengths;

    @Property(name = "weaknesses")
    public List<String> weaknesses;

    @Property(name = "suggestions")
    public List<String> suggestions;

    @Property(name = "confidence")
    public Double confidence;

    @Property(name = "improved_solution")
    public String improvedSolution;

    @Property(name = "should_continue")
    public Boolean shouldContinue;

    public int getScore() {
        return score;
    }

    public boolean isPass() {
        return pass;
    }

    public List<String> getStrengths() {
        return strengths != null ? strengths : List.of();
    }

    public List<String> getWeaknesses() {
        return weaknesses != null ? weaknesses : List.of();
    }

    public List<String> getSuggestions() {
        return suggestions != null ? suggestions : List.of();
    }

    public double getConfidence() {
        return confidence;
    }

    public String getImprovedSolution() {
        return improvedSolution;
    }

    public boolean isShouldContinue() {
        return shouldContinue;
    }

    public boolean isValid() {
        return score >= 1
                && score <= 10
                && weaknesses != null
                && !weaknesses.isEmpty()
                && suggestions != null
                && !suggestions.isEmpty();
    }

    @Override
    public String toString() {
        return "ReflectionEvaluation{"
                + "score=" + score
                + ", pass=" + pass
                + ", confidence=" + confidence
                + ", shouldContinue=" + shouldContinue
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReflectionEvaluation that = (ReflectionEvaluation) o;
        return score == that.score
                && pass == that.pass
                && Double.compare(that.confidence, confidence) == 0
                && shouldContinue == that.shouldContinue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(score, pass, confidence, shouldContinue);
    }

    public static final class Builder {
        private int score;
        private boolean pass;
        private List<String> strengths;
        private List<String> weaknesses;
        private List<String> suggestions;
        private double confidence;
        private String improvedSolution;
        private boolean shouldContinue = true;

        private Builder() {
        }

        public Builder score(int score) {
            this.score = score;
            return this;
        }

        public Builder pass(boolean pass) {
            this.pass = pass;
            return this;
        }

        public Builder strengths(List<String> strengths) {
            this.strengths = strengths;
            return this;
        }

        public Builder weaknesses(List<String> weaknesses) {
            this.weaknesses = weaknesses;
            return this;
        }

        public Builder suggestions(List<String> suggestions) {
            this.suggestions = suggestions;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder improvedSolution(String improvedSolution) {
            this.improvedSolution = improvedSolution;
            return this;
        }

        public Builder withShouldContinue(boolean shouldContinue) {
            this.shouldContinue = shouldContinue;
            return this;
        }

        public ReflectionEvaluation build() {
            ReflectionEvaluation evaluation = new ReflectionEvaluation();
            evaluation.score = score;
            evaluation.pass = pass;
            evaluation.strengths = strengths;
            evaluation.weaknesses = weaknesses;
            evaluation.suggestions = suggestions;
            evaluation.confidence = confidence;
            evaluation.improvedSolution = improvedSolution;
            evaluation.shouldContinue = shouldContinue;
            return evaluation;
        }
    }
}