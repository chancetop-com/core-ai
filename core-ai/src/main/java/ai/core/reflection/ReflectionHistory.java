package ai.core.reflection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the complete history of a reflection process.
 * Records each evaluation round, token usage, and overall progress.
 *
 * @author xander
 */
public final class ReflectionHistory {
    private final String agentId;
    private final String agentName;
    private final String initialInput;
    private final String evaluationCriteria;
    private final List<ReflectionRound> rounds;
    private final Instant startTime;
    private Instant endTime;
    private ReflectionStatus status;

    /**
     * Creates a new reflection history.
     *
     * @param agentId agent identifier
     * @param agentName agent name
     * @param initialInput the original task/input
     * @param evaluationCriteria evaluation criteria string
     */
    public ReflectionHistory(String agentId, String agentName, String initialInput, String evaluationCriteria) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.initialInput = initialInput;
        this.evaluationCriteria = evaluationCriteria;
        this.rounds = new ArrayList<>();
        this.startTime = Instant.now();
        this.status = ReflectionStatus.IN_PROGRESS;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getInitialInput() {
        return initialInput;
    }

    public String getEvaluationCriteria() {
        return evaluationCriteria;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Duration getTotalDuration() {
        return Duration.between(startTime, endTime);
    }

    public List<ReflectionRound> getRounds() {
        return rounds;
    }

    public ReflectionStatus getStatus() {
        return status;
    }

    public void addRound(ReflectionRound round) {
        this.rounds.add(round);
    }

    /**
     * complete reflection
     *
     * @param finalStatus the final status of reflection
     */
    public void complete(ReflectionStatus finalStatus) {
        this.endTime = Instant.now();
        this.status = finalStatus;
    }

    /**
     * Get total tokens used across all rounds.
     *
     * @return total token count
     */
    public int getTotalTokensUsed() {
        return rounds.stream()
                .map(ReflectionRound::getTotalTokens)
                .mapToInt(Long::intValue)
                .sum();
    }

    /**
     * Get the final score from the last round.
     *
     * @return final score, or 0 if no rounds
     */
    public int getFinalScore() {
        if (rounds.isEmpty()) {
            return 0;
        }
        return rounds.getLast().getEvaluation().getScore();
    }

    /**
     * Calculate average improvement rate between rounds.
     *
     * @return average improvement rate
     */
    public double getAverageImprovementRate() {
        if (rounds.size() < 2) {
            return 0.0;
        }

        int improvements = 0;
        for (int i = 1; i < rounds.size(); i++) {
            if (rounds.get(i).getEvaluation().getScore()
                    > rounds.get(i - 1).getEvaluation().getScore()) {
                improvements++;
            }
        }

        return (double) improvements / (rounds.size() - 1);
    }

    /**
     * Get the round with the highest score.
     *
     * @return best round, or null if no rounds
     */
    public ReflectionRound getBestRound() {
        return rounds.stream()
                .max((r1, r2) -> Integer.compare(
                        r1.getEvaluation().getScore(),
                        r2.getEvaluation().getScore()))
                .orElse(null);
    }

    /**
     * Check if there was continuous improvement throughout the reflection.
     *
     * @return true if each round improved on the previous
     */
    public boolean hasContinuousImprovement() {
        if (rounds.size() < 2) {
            return false;
        }

        for (int i = 1; i < rounds.size(); i++) {
            if (rounds.get(i).getEvaluation().getScore()
                    <= rounds.get(i - 1).getEvaluation().getScore()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Generate a summary of the reflection process.
     *
     * @return summary string
     */
    public String generateSummary() {
        return "Reflection Summary for Agent: " + agentName + '\n'
                + "Status: " + status + '\n'
                + "Total Rounds: " + rounds.size() + '\n'
                + "Total Tokens: " + getTotalTokensUsed() + '\n'
                + "Final Score: " + getFinalScore() + '\n'
                + "Duration: " + getTotalDuration().toSeconds() + "s\n"
                + "Continuous Improvement: " + hasContinuousImprovement() + '\n';
    }

    /**
     * Represents a single reflection round with evaluation results.
     */
    public record ReflectionRound(
            int roundNumber,
            String evaluationInput,
            String evaluationOutput,
            ReflectionEvaluation evaluation,
            Duration roundDuration,
            Long totalTokens) {

        public int getRoundNumber() {
            return roundNumber;
        }

        public String getEvaluationInput() {
            return evaluationInput;
        }

        public String getEvaluationOutput() {
            return evaluationOutput;
        }

        public ReflectionEvaluation getEvaluation() {
            return evaluation;
        }

        public Duration getRoundDuration() {
            return roundDuration;
        }

        public Long getTotalTokens() {
            return totalTokens;
        }

        public Instant getRoundEnd() {
            return Instant.now();
        }

        public boolean isImprovement(ReflectionRound previous) {
            return this.evaluation.getScore() > previous.evaluation.getScore();
        }
    }
}