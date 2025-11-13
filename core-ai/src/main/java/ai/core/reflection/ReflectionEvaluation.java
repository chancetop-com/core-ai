package ai.core.reflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 改进版：结构化的Reflection评估结果
 * Improved: Structured reflection evaluation result with better validation
 *
 * @author xander
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // 忽略未知字段，提高容错性
public class ReflectionEvaluation{

    @JsonProperty("score")
    private int score;  // 1-10分

    @JsonProperty("pass")
    private boolean pass;  // 是否通过

    @JsonProperty("strengths")
    private List<String> strengths = Collections.emptyList();  // 初始化为空列表，避免NPE

    @JsonProperty("weaknesses")
    private List<String> weaknesses = Collections.emptyList();

    @JsonProperty("suggestions")
    private List<String> suggestions = Collections.emptyList();

    @JsonProperty("dimensions")
    private Map<String, Integer> dimensionScores = Collections.emptyMap();

    @JsonProperty("confidence")
    private double confidence = 0.5;  // 默认置信度

    @JsonProperty("improved_solution")
    private String improvedSolution;

    @JsonProperty("should_continue")
    private boolean shouldContinue = true;  // 默认继续

    // 构造函数
    public ReflectionEvaluation() {
        // 默认构造函数，用于Jackson反序列化
    }

    /**
     * 创建Builder模式，提供更好的构造方式
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ReflectionEvaluation evaluation = new ReflectionEvaluation();

        public Builder score(int score) {
            if (score < 1 || score > 10) {
                throw new IllegalArgumentException("Score must be between 1 and 10");
            }
            evaluation.score = score;
            return this;
        }

        public Builder pass(boolean pass) {
            evaluation.pass = pass;
            return this;
        }

        public Builder strengths(List<String> strengths) {
            evaluation.strengths = strengths != null ? List.copyOf(strengths) : Collections.emptyList();
            return this;
        }

        public Builder weaknesses(List<String> weaknesses) {
            evaluation.weaknesses = weaknesses != null ? List.copyOf(weaknesses) : Collections.emptyList();
            return this;
        }

        public Builder suggestions(List<String> suggestions) {
            evaluation.suggestions = suggestions != null ? List.copyOf(suggestions) : Collections.emptyList();
            return this;
        }

        public Builder dimensionScores(Map<String, Integer> scores) {
            if (scores != null) {
                // 验证维度分数范围
                scores.forEach((key, value) -> {
                    if (value < 1 || value > 10) {
                        throw new IllegalArgumentException(
                                String.format("Dimension score for '%s' must be between 1 and 10", key)
                        );
                    }
                });
                evaluation.dimensionScores = Map.copyOf(scores);
            }
            return this;
        }

        public Builder confidence(double confidence) {
            if (confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("Confidence must be between 0 and 1");
            }
            evaluation.confidence = confidence;
            return this;
        }

        public Builder improvedSolution(String solution) {
            evaluation.improvedSolution = solution;
            return this;
        }

        public Builder shouldContinue(boolean shouldContinue) {
            evaluation.shouldContinue = shouldContinue;
            return this;
        }

        public ReflectionEvaluation build() {
            // 自动设置pass基于score
            if (evaluation.score >= 8 && !evaluation.pass) {
                evaluation.pass = true;
            }
            return evaluation;
        }
    }

    // Getters (返回防御性副本或不可变视图)
    public int getScore() {
        return score;
    }

    public boolean isPass() {
        return pass;
    }

    public List<String> getStrengths() {
        return Collections.unmodifiableList(strengths);
    }

    public List<String> getWeaknesses() {
        return Collections.unmodifiableList(weaknesses);
    }

    public List<String> getSuggestions() {
        return Collections.unmodifiableList(suggestions);
    }

    public Map<String, Integer> getDimensionScores() {
        return Collections.unmodifiableMap(dimensionScores);
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

    // Setters (用于Jackson，添加验证)
    public void setScore(int score) {
        if (score < 1 || score > 10) {
            throw new IllegalArgumentException("Score must be between 1 and 10");
        }
        this.score = score;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths != null ? List.copyOf(strengths) : Collections.emptyList();
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses != null ? List.copyOf(weaknesses) : Collections.emptyList();
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions != null ? List.copyOf(suggestions) : Collections.emptyList();
    }

    public void setDimensionScores(Map<String, Integer> dimensionScores) {
        this.dimensionScores = dimensionScores != null ?
                Map.copyOf(dimensionScores) : Collections.emptyMap();
    }

    public void setConfidence(double confidence) {
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1");
        }
        this.confidence = confidence;
    }

    public void setImprovedSolution(String improvedSolution) {
        this.improvedSolution = improvedSolution;
    }

    public void setShouldContinue(boolean shouldContinue) {
        this.shouldContinue = shouldContinue;
    }

    /**
     * 计算加权总分（改进版，添加验证）
     */
    public double getWeightedScore(Map<String, Double> weights) {
        if (dimensionScores.isEmpty() || weights == null || weights.isEmpty()) {
            return score;
        }

        double weightedSum = 0;
        double totalWeight = 0;

        for (Map.Entry<String, Integer> entry : dimensionScores.entrySet()) {
            Double weight = weights.get(entry.getKey());
            if (weight != null && weight > 0) {
                weightedSum += entry.getValue() * weight;
                totalWeight += weight;
            }
        }

        return totalWeight > 0 ? weightedSum / totalWeight : score;
    }

    /**
     * 检查是否有改进空间
     */
    public boolean hasRoomForImprovement() {
        return score < 10 || !weaknesses.isEmpty() || shouldContinue;
    }

    /**
     * 获取最主要的问题（用于重点改进）
     */
    public String getMajorIssue() {
        if (weaknesses.isEmpty()) {
            return null;
        }
        return weaknesses.get(0);  // 假设第一个是最重要的
    }

    /**
     * 获取改进优先级（基于维度分数）
     */
    public String getImprovementPriority() {
        if (dimensionScores.isEmpty()) {
            return null;
        }

        return dimensionScores.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReflectionEvaluation that = (ReflectionEvaluation) o;
        return score == that.score &&
                pass == that.pass &&
                Double.compare(that.confidence, confidence) == 0 &&
                shouldContinue == that.shouldContinue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(score, pass, confidence, shouldContinue);
    }

    @Override
    public String toString() {
        return String.format(
                "ReflectionEvaluation{score=%d, pass=%s, confidence=%.2f, strengths=%d, weaknesses=%d}",
                score, pass, confidence,
                strengths != null ? strengths.size() : 0,
                weaknesses != null ? weaknesses.size() : 0
        );
    }
}