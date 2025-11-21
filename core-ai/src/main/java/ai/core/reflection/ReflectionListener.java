package ai.core.reflection;

import ai.core.agent.Agent;

/**
 * Callback interface for reflection events.
 * Provides hooks for monitoring and responding to the reflection lifecycle.
 *
 * @author xander
 */
public interface ReflectionListener {

    /**
     * Called before reflection process starts.
     *
     * @param agent the agent performing reflection
     * @param task the original task input
     * @param evaluationCriteria the evaluation criteria being used
     */
    default void onReflectionStart(Agent agent, String task, String evaluationCriteria) {
        // Default empty implementation
    }

    /**
     * Called before each reflection round begins.
     *
     * @param agent the agent performing reflection
     * @param round the current round number
     * @param input the input for this round
     */
    default void onBeforeRound(Agent agent, int round, String input) {
        // Default empty implementation
    }

    /**
     * Called after each reflection round completes.
     *
     * @param agent the agent performing reflection
     * @param round the current round number
     * @param output the output generated in this round
     * @param evaluation the evaluation result (if parseable)
     */
    default void onAfterRound(Agent agent, int round, String output, ReflectionEvaluation evaluation) {
        // Default empty implementation
    }

    /**
     * Called when reflection terminates due to achieving target score.
     *
     * @param agent the agent performing reflection
     * @param finalScore the final score achieved
     * @param rounds the total number of rounds executed
     */
    default void onScoreAchieved(Agent agent, int finalScore, int rounds) {
        // Default empty implementation
    }

    /**
     * Called when reflection terminates due to no improvement detected.
     *
     * @param agent the agent performing reflection
     * @param lastScore the last score achieved
     * @param rounds the total number of rounds executed
     */
    default void onNoImprovement(Agent agent, int lastScore, int rounds) {
        // Default empty implementation
    }

    /**
     * Called when reflection terminates due to reaching maximum rounds.
     *
     * @param agent the agent performing reflection
     * @param finalScore the final score achieved
     */
    default void onMaxRoundsReached(Agent agent, int finalScore) {
        // Default empty implementation
    }

    /**
     * Called after reflection process completes.
     *
     * @param agent the agent performing reflection
     * @param history the complete reflection history
     */
    default void onReflectionComplete(Agent agent, ReflectionHistory history) {
        // Default empty implementation
    }

    /**
     * Called when an error occurs during reflection process.
     *
     * @param agent the agent performing reflection
     * @param round the current round number when error occurred
     * @param error the error that occurred
     */
    default void onError(Agent agent, int round, Exception error) {
        // Default empty implementation
    }
}