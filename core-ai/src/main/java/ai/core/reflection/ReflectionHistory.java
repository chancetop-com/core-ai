package ai.core.reflection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection历史记录 - 追踪整个reflection过程
 * Tracks the complete reflection process history
 *
 * @author xander
 */
public class ReflectionHistory {

    private final String agentId;
    private final String agentName;
    private final String originalTask;
    private final String evaluationCriteria;
    private final List<ReflectionRound> rounds;
    private final Instant startTime;
    private Instant endTime;
    private ReflectionStatus status;

    public enum ReflectionStatus {
        IN_PROGRESS,
        COMPLETED_SUCCESS,  // 达到质量标准
        COMPLETED_MAX_ROUNDS,  // 达到最大轮数
        COMPLETED_NO_IMPROVEMENT,  // 无改进空间
        FAILED,
        INTERRUPTED
    }

    /**
     * 单轮reflection记录
     */
    public static class ReflectionRound {
        private final int roundNumber;
        private final String input;
        private final String output;
        private final ReflectionEvaluation evaluation;
        private final Instant timestamp;
        private final Duration duration;
        private final int tokensUsed;
        private double improvementRate;  // 相对上轮的改进率

        public ReflectionRound(int roundNumber, String input, String output,
                               ReflectionEvaluation evaluation, Duration duration, int tokensUsed) {
            this.roundNumber = roundNumber;
            this.input = input;
            this.output = output;
            this.evaluation = evaluation;
            this.timestamp = Instant.now();
            this.duration = duration;
            this.tokensUsed = tokensUsed;
            this.improvementRate = 0;
        }

        // Getters
        public int getRoundNumber() { return roundNumber; }
        public String getInput() { return input; }
        public String getOutput() { return output; }
        public ReflectionEvaluation getEvaluation() { return evaluation; }
        public Instant getTimestamp() { return timestamp; }
        public Duration getDuration() { return duration; }
        public int getTokensUsed() { return tokensUsed; }
        public double getImprovementRate() { return improvementRate; }

        public void setImprovementRate(double rate) {
            this.improvementRate = rate;
        }
    }

    public ReflectionHistory(String agentId, String agentName, String originalTask,
                             String evaluationCriteria) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.originalTask = originalTask;
        this.evaluationCriteria = evaluationCriteria;
        this.rounds = new ArrayList<>();
        this.startTime = Instant.now();
        this.status = ReflectionStatus.IN_PROGRESS;
    }

    /**
     * 添加新的reflection轮次
     */
    public void addRound(ReflectionRound round) {
        // 计算改进率
        if (!rounds.isEmpty()) {
            ReflectionRound lastRound = rounds.get(rounds.size() - 1);
            double lastScore = lastRound.getEvaluation().getScore();
            double currentScore = round.getEvaluation().getScore();
            double improvement = ((currentScore - lastScore) / lastScore) * 100;
            round.setImprovementRate(improvement);
        }
        rounds.add(round);
    }

    /**
     * 完成reflection过程
     */
    public void complete(ReflectionStatus finalStatus) {
        this.endTime = Instant.now();
        this.status = finalStatus;
    }

    /**
     * 获取总耗时
     */
    public Duration getTotalDuration() {
        Instant end = endTime != null ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }

    /**
     * 获取总token使用量
     */
    public int getTotalTokensUsed() {
        return rounds.stream()
                .mapToInt(ReflectionRound::getTokensUsed)
                .sum();
    }

    /**
     * 获取最终得分
     */
    public int getFinalScore() {
        if (rounds.isEmpty()) return 0;
        return rounds.get(rounds.size() - 1).getEvaluation().getScore();
    }

    /**
     * 获取平均改进率
     */
    public double getAverageImprovementRate() {
        if (rounds.size() <= 1) return 0;
        return rounds.stream()
                .skip(1)  // 跳过第一轮（没有改进率）
                .mapToDouble(ReflectionRound::getImprovementRate)
                .average()
                .orElse(0);
    }

    /**
     * 获取最佳轮次（得分最高）
     */
    public ReflectionRound getBestRound() {
        return rounds.stream()
                .max((r1, r2) -> Integer.compare(
                        r1.getEvaluation().getScore(),
                        r2.getEvaluation().getScore()))
                .orElse(null);
    }

    /**
     * 检查是否有持续改进
     */
    public boolean hasContinuousImprovement() {
        if (rounds.size() <= 1) return false;

        for (int i = 1; i < rounds.size(); i++) {
            if (rounds.get(i).getImprovementRate() <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成reflection摘要报告
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Reflection Summary for %s\n", agentName));
        summary.append("=".repeat(50)).append("\n");
        summary.append(String.format("Task: %s\n", originalTask));
        summary.append(String.format("Total Rounds: %d\n", rounds.size()));
        summary.append(String.format("Total Duration: %s\n", getTotalDuration()));
        summary.append(String.format("Total Tokens: %d\n", getTotalTokensUsed()));
        summary.append(String.format("Final Score: %d\n", getFinalScore()));
        summary.append(String.format("Average Improvement Rate: %.2f%%\n", getAverageImprovementRate()));
        summary.append(String.format("Status: %s\n", status));

        if (!rounds.isEmpty()) {
            summary.append("\nRound Details:\n");
            for (ReflectionRound round : rounds) {
                summary.append(String.format("  Round %d: Score=%d, Improvement=%.1f%%, Time=%s\n",
                        round.getRoundNumber(),
                        round.getEvaluation().getScore(),
                        round.getImprovementRate(),
                        round.getDuration()));
            }
        }

        return summary.toString();
    }

    // Getters
    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getOriginalTask() { return originalTask; }
    public String getEvaluationCriteria() { return evaluationCriteria; }
    public List<ReflectionRound> getRounds() { return rounds; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public ReflectionStatus getStatus() { return status; }
}