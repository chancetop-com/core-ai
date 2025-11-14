package ai.core.reflection;

import ai.core.agent.Agent;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.prompt.engines.MustachePromptTemplate;
import core.framework.crypto.Hash;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executor for agent reflection process.
 * Handles the iterative self-improvement mechanism where an agent
 * evaluates and refines its output through multiple rounds.
 *
 * @author xander
 */
public final class ReflectionExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReflectionExecutor.class);

    private final Agent agent;
    private final ReflectionConfig config;
    private final Map<String, Object> variables;
    private final ReflectionListener listener;

    private ReflectionHistory history;

    /**
     * Creates a new reflection executor.
     *
     * @param agent the agent performing reflection
     * @param config reflection configuration
     * @param variables template variables for prompt rendering
     * @param listener optional listener for reflection events (can be null)
     */
    public ReflectionExecutor(Agent agent, ReflectionConfig config, Map<String, Object> variables, ReflectionListener listener) {
        if (agent == null) {
            throw new IllegalArgumentException("Agent cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("ReflectionConfig cannot be null");
        }
        this.agent = agent;
        this.config = config;
        this.variables = variables;
        this.listener = listener;
    }

    /**
     * Convenience constructor without listener.
     */
    public ReflectionExecutor(Agent agent, ReflectionConfig config, Map<String, Object> variables) {
        this(agent, config, variables, null);
    }

    /**
     * Execute the reflection process.
     * This will iterate through multiple rounds of evaluation and improvement
     * until a termination condition is met.
     */
    public void execute() {
        // Validate configuration
        validateConfiguration();

        // Initialize reflection history
        history = new ReflectionHistory(
                agent.getId(),
                agent.getName(),
                agent.getInput(),
                config.evaluationCriteria()
        );

        // Initialize round counter
        agent.setRound(1);

        // Notify listener
        notifyReflectionStart();

        try {
            // Main reflection loop
            while (shouldContinueReflection()) {
                executeReflectionRound();
                agent.setRound(agent.getRound() + 1);
            }

            // Complete history
            history.complete(determineCompletionStatus());
            notifyReflectionComplete();

        } catch (Exception e) {
            LOGGER.error("Reflection failed for agent {}: {}", agent.getName(), e.getMessage(), e);
            history.complete(ReflectionStatus.FAILED);
            notifyError(e);
            throw new RuntimeException("Reflection execution failed", e);
        }
    }

    /**
     * Execute a single reflection round.
     */
    private void executeReflectionRound() {
        int currentRound = agent.getRound();
        LOGGER.info("Reflection round: {}/{}, agent: {}, input: {}, output: {}",
                currentRound, agent.getMaxRound(), agent.getName(), agent.getInput(), agent.getOutput());

        Instant roundStart = Instant.now();

        // Notify listener before round
        notifyBeforeRound(currentRound, agent.getOutput());

        // Step 1: Evaluate current solution in independent LLM context
        String solutionToEvaluate = agent.getOutput();
        String evaluationText = evaluateInIndependentContext();

        // Step 2: Parse evaluation to structured object
        ReflectionEvaluation evaluation = parseEvaluation(evaluationText);

        // Log parsed evaluation details
        LOGGER.info("Round {} evaluation parsed: score={}, pass={}, shouldContinue={}, weaknesses={}, suggestions={}",
                currentRound, evaluation.getScore(), evaluation.isPass(), evaluation.isShouldContinue(),
                evaluation.getWeaknesses().size(), evaluation.getSuggestions().size());

        // Step 3: Check structured termination conditions
        if (shouldTerminateReflection(evaluation)) {
            LOGGER.info("Reflection terminating: score={}, pass={}, shouldContinue={}",
                    evaluation.getScore(), evaluation.isPass(), evaluation.isShouldContinue());

            // Record final round before terminating
            recordRound(currentRound, solutionToEvaluate, evaluationText, evaluation, roundStart);

            // Notify listener based on termination reason
            if (evaluation.isPass() && evaluation.getScore() >= 8) {
                notifyScoreAchieved(evaluation.getScore(), currentRound);
            } else if (!evaluation.isShouldContinue()) {
                notifyNoImprovement(evaluation.getScore(), currentRound);
            }

            return; // Exit reflection loop
        }

        // Step 4: Agent regenerates solution based on feedback
        String improvementPrompt = buildImprovementPrompt(evaluationText, evaluation);
        agent.chatCore(improvementPrompt, variables);

        // Step 5: Record this round to history
        recordRound(currentRound, solutionToEvaluate, evaluationText, evaluation, roundStart);

        // Notify listener after round
        notifyAfterRound(currentRound, agent.getOutput(), evaluation);
    }

    /**
     * Evaluate current solution in an independent LLM context.
     */
    private String evaluateInIndependentContext() {
        // Build evaluator system prompt
        String evaluatorSystemPrompt = buildEvaluatorSystemPrompt();

        // Build evaluation user message (the solution to evaluate)
        String evaluationUserMessage = buildEvaluationUserMessage();

        // Create independent message list for evaluation (not using Agent's history)
        List<Message> evaluationMessages = List.of(
                Message.of(RoleType.SYSTEM, evaluatorSystemPrompt, agent.getName() + "-evaluator"),
                Message.of(RoleType.USER, evaluationUserMessage, null, null, null, null)
        );

        // Call LLM in independent context with JSON response format
        CompletionRequest evalRequest = CompletionRequest.of(
                evaluationMessages,
                null,
                agent.getTemperature(),
                agent.getModel(),
                agent.getName() + "-evaluator"
        );
        evalRequest.responseFormat = ai.core.llm.domain.ResponseFormat.json();

        CompletionResponse evalResponse = agent.getLLMProvider().completion(evalRequest);

        // Track token usage
        agent.addTokenCost(evalResponse.usage);

        return evalResponse.choices.getFirst().message.content;
    }

    /**
     * Build evaluator system prompt with task context and evaluation criteria.
     */
    private String buildEvaluatorSystemPrompt() {
        String evaluationPrompt = config.prompt();

        if (config.evaluationCriteria() != null && !config.evaluationCriteria().isEmpty()) {
            Map<String, Object> evalContext = new HashMap<>(variables != null ? variables : Map.of());
            evalContext.put("task", agent.getInput());
            evalContext.put("evaluationCriteria", config.evaluationCriteria());

            evaluationPrompt = new MustachePromptTemplate().execute(
                    evaluationPrompt,
                    evalContext,
                    Hash.md5Hex(evaluationPrompt)
            );
        }

        return evaluationPrompt;
    }

    /**
     * Build the user message for evaluation (the solution to be evaluated).
     */
    private String buildEvaluationUserMessage() {
        return String.format("""
                **Solution to Evaluate:**

                %s

                Please provide your evaluation in the JSON format specified in the system prompt.
                """, agent.getOutput());
    }

    /**
     * Parse evaluation text to structured ReflectionEvaluation object.
     * Since we request JSON format from the LLM, we expect direct JSON response.
     */
    private ReflectionEvaluation parseEvaluation(String evaluationText) {
        LOGGER.info("=== Parsing evaluation JSON ===");
        LOGGER.info("Raw JSON length: {}", evaluationText.length());
        LOGGER.info("Raw JSON content: {}", evaluationText);

        // Parse JSON directly to ReflectionEvaluation
        ReflectionEvaluation evaluation;
        try {
            evaluation = JSON.fromJSON(ReflectionEvaluation.class, evaluationText);
            LOGGER.info("JSON.fromJSON completed successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to parse evaluation JSON: {}", evaluationText, e);
            throw new IllegalStateException("Failed to deserialize evaluation JSON", e);
        }

        // Log detailed parsed values
        LOGGER.info("=== Parsed evaluation details ===");
        LOGGER.info("  score: {}", evaluation.getScore());
        LOGGER.info("  pass: {}", evaluation.isPass());
        LOGGER.info("  shouldContinue: {}", evaluation.isShouldContinue());
        LOGGER.info("  confidence: {}", evaluation.getConfidence());
        LOGGER.info("  strengths: {}", evaluation.getStrengths());
        LOGGER.info("  weaknesses: {}", evaluation.getWeaknesses());
        LOGGER.info("  suggestions: {}", evaluation.getSuggestions());
        LOGGER.info("  dimensions: {}", evaluation.getDimensionScores());
        LOGGER.info("  toString: {}", evaluation);

        // Validate the parsed evaluation
        if (evaluation.getScore() < 1 || evaluation.getScore() > 10) {
            throw new IllegalStateException(Strings.format(
                    "Invalid evaluation score: {}. Score must be between 1 and 10.",
                    evaluation.getScore()
            ));
        }

        return evaluation;
    }

    /**
     * Check if reflection should terminate based on evaluation.
     */
    private boolean shouldTerminateReflection(ReflectionEvaluation evaluation) {
        // Terminate if pass criteria met and score is good
        if (evaluation.isPass() && evaluation.getScore() >= 8) {
            return true;
        }

        // Terminate if evaluation explicitly says not to continue
        if (!evaluation.isShouldContinue()) {
            return true;
        }

        // Terminate if minimum rounds completed and score is acceptable
        if (agent.getRound() >= config.minRound() && evaluation.getScore() >= 7) {
            return true;
        }

        return false;
    }

    /**
     * Build improvement prompt based on evaluation feedback.
     */
    private String buildImprovementPrompt(String evaluationText, ReflectionEvaluation evaluation) {
        StringBuilder prompt = new StringBuilder(256);

        prompt.append("Based on the evaluation feedback, please improve your solution.\n\n**Evaluation Feedback:**\n")
              .append(evaluationText).append("\n\n");

        if (!evaluation.getWeaknesses().isEmpty()) {
            prompt.append("**Key Issues to Address:**\n");
            for (String weakness : evaluation.getWeaknesses()) {
                prompt.append("- ").append(weakness).append('\n');
            }
            prompt.append('\n');
        }

        if (!evaluation.getSuggestions().isEmpty()) {
            prompt.append("**Improvement Suggestions:**\n");
            for (String suggestion : evaluation.getSuggestions()) {
                prompt.append("- ").append(suggestion).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("Please provide an improved solution that addresses these points.");

        return prompt.toString();
    }

    /**
     * Check if reflection should continue.
     */
    private boolean shouldContinueReflection() {
        // Check max rounds
        if (agent.getRound() > agent.getMaxRound()) {
            LOGGER.info("Max rounds ({}) reached for agent {}", agent.getMaxRound(), agent.getName());
            notifyMaxRoundsReached();
            return false;
        }

        // Check if there are termination conditions
        if (!agent.notTerminated()) {
            return false;
        }

        return true;
    }

    /**
     * Record a reflection round to history.
     */
    private void recordRound(int roundNumber, String evaluationInput, String evaluationOutput,
                             ReflectionEvaluation evaluation, Instant roundStart) {
        Duration roundDuration = Duration.between(roundStart, Instant.now());
        ReflectionHistory.ReflectionRound round = new ReflectionHistory.ReflectionRound(
                roundNumber,
                evaluationInput,
                evaluationOutput,
                evaluation,
                roundDuration,
                (long) agent.getCurrentTokenUsage().getTotalTokens()
        );
        history.addRound(round);
    }

    /**
     * Determine the completion status based on current state.
     */
    private ReflectionStatus determineCompletionStatus() {
        if (agent.getRound() > agent.getMaxRound()) {
            return ReflectionStatus.COMPLETED_MAX_ROUNDS;
        }

        if (!history.getRounds().isEmpty()) {
            ReflectionEvaluation lastEval = history.getRounds().getLast().getEvaluation();
            if (lastEval.isPass() && lastEval.getScore() >= 8) {
                return ReflectionStatus.COMPLETED_SUCCESS;
            }
            if (!lastEval.isShouldContinue()) {
                return ReflectionStatus.COMPLETED_NO_IMPROVEMENT;
            }
        }

        return ReflectionStatus.COMPLETED_SUCCESS;
    }

    /**
     * Validate reflection configuration.
     */
    private void validateConfiguration() {
        if (agent.getTerminations().isEmpty()) {
            throw new RuntimeException(Strings.format(
                    "Reflection agent must have termination: {}<{}>",
                    agent.getName(), agent.getId()
            ));
        }

        if (config.maxRound() < 1) {
            throw new IllegalArgumentException("maxRound must be at least 1");
        }

        if (config.minRound() < 0) {
            throw new IllegalArgumentException("minRound cannot be negative");
        }

        if (config.minRound() > config.maxRound()) {
            throw new IllegalArgumentException("minRound cannot exceed maxRound");
        }
    }

    // ==================== Listener Notification Methods ====================

    private void notifyReflectionStart() {
        if (listener != null) {
            listener.onReflectionStart(agent, agent.getInput(), config.evaluationCriteria());
        }
    }

    private void notifyBeforeRound(int round, String input) {
        if (listener != null) {
            listener.onBeforeRound(agent, round, input);
        }
    }

    private void notifyAfterRound(int round, String output, ReflectionEvaluation evaluation) {
        if (listener != null) {
            listener.onAfterRound(agent, round, output, evaluation);
        }
    }

    private void notifyScoreAchieved(int finalScore, int rounds) {
        if (listener != null) {
            listener.onScoreAchieved(agent, finalScore, rounds);
        }
    }

    private void notifyNoImprovement(int lastScore, int rounds) {
        if (listener != null) {
            listener.onNoImprovement(agent, lastScore, rounds);
        }
    }

    private void notifyMaxRoundsReached() {
        if (listener != null) {
            int finalScore = history.getRounds().isEmpty()
                    ? 0
                    : history.getRounds().getLast().getEvaluation().getScore();
            listener.onMaxRoundsReached(agent, finalScore);
        }
    }

    private void notifyReflectionComplete() {
        if (listener != null) {
            listener.onReflectionComplete(agent, history);
        }
    }

    private void notifyError(Exception error) {
        if (listener != null) {
            listener.onError(agent, agent.getRound(), error);
        }
    }

    // ==================== Getters ====================

    public ReflectionHistory getHistory() {
        return history;
    }

    public ReflectionConfig getConfig() {
        return config;
    }
}