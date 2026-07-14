package ai.core.agent;

import ai.core.agent.internal.AgentHelper;
import ai.core.reflection.ReflectionEvaluation;
import ai.core.reflection.ReflectionEvaluator;
import ai.core.reflection.ReflectionHistory;
import ai.core.reflection.ReflectionStatus;
import core.framework.json.JSON;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * @author stephen
 */
final class ReflectionOrchestrator {

    static void reflectionLoop(Agent agent, Map<String, Object> variables) {
        ReflectionHistory history = new ReflectionHistory(agent.getId(), agent.getName(), agent.getInput(), agent.reflectionConfig.evaluationCriteria());
        int currentRound = 1;
        agent.setRound(currentRound);
        if (agent.reflectionListener != null)
            agent.reflectionListener.onReflectionStart(agent, agent.getInput(), agent.reflectionConfig.evaluationCriteria());
        while (currentRound <= agent.reflectionConfig.maxRound()) {
            AgentInterruptionHandler.throwIfCancelled(agent);
            agent.setRound(currentRound);
            Instant roundStart = Instant.now();
            String solutionToEvaluate = agent.getOutput();
            agent.logger.debug("Reflection round: {}/{}, agent: {}", currentRound, agent.reflectionConfig.maxRound(), agent.getName());
            if (agent.reflectionListener != null) agent.reflectionListener.onBeforeRound(agent, currentRound, solutionToEvaluate);
            var evalRequest = new ReflectionEvaluator.EvaluationRequest(
                    agent.getInput(), agent.getOutput(), agent.getName(), agent.getLLMProvider(),
                    agent.getTemperature(), agent.getModel(), agent.reflectionConfig, variables);
            ReflectionEvaluator.EvaluationResult evalResult = ReflectionEvaluator.evaluate(evalRequest);
            agent.addTokenCost(evalResult.usage());
            String evaluationJson = evalResult.evaluationJson();
            ReflectionEvaluation evaluation = JSON.fromJSON(ReflectionEvaluation.class, evaluationJson);
            if (!AgentHelper.isValidEvaluation(evaluation)) {
                agent.logger.error("Invalid evaluation score: {}, terminating reflection", evaluation.getScore());
                history.complete(ReflectionStatus.FAILED);
                if (agent.reflectionListener != null) agent.reflectionListener.onError(agent, currentRound,
                        new IllegalStateException("Invalid evaluation score: " + evaluation.getScore()));
                return;
            }
            agent.logger.debug("Round {} evaluation: score={}, pass={}, continue={}",
                    currentRound, evaluation.getScore(), evaluation.isPass(), evaluation.isShouldContinue());
            history.addRound(new ReflectionHistory.ReflectionRound(currentRound, solutionToEvaluate, evaluationJson,
                    evaluation, Duration.between(roundStart, Instant.now()), (long) agent.getCurrentTokenUsage().getTotalTokens()));
            if (AgentHelper.shouldTerminateReflection(agent.reflectionConfig, evaluation, currentRound)) {
                agent.logger.debug("Reflection terminating: score={}, pass={}", evaluation.getScore(), evaluation.isPass());
                notifyTerminationReason(agent, evaluation, currentRound);
                break;
            }
            agent.doExecute(ReflectionEvaluator.buildImprovementPrompt(evaluationJson, evaluation), variables, true);
            if (agent.reflectionListener != null)
                agent.reflectionListener.onAfterRound(agent, currentRound, agent.getOutput(), evaluation);
            currentRound++;
        }
        if (agent.reflectionListener != null && currentRound > agent.reflectionConfig.maxRound()) {
            int finalScore = history.getRounds().isEmpty() ? 0 : history.getRounds().getLast().getEvaluation().getScore();
            agent.reflectionListener.onMaxRoundsReached(agent, finalScore);
        }
        history.complete(determineCompletionStatus(agent, history));
        if (agent.reflectionListener != null) agent.reflectionListener.onReflectionComplete(agent, history);
    }

    static void notifyTerminationReason(Agent agent, ReflectionEvaluation eval, int round) {
        if (agent.reflectionListener == null) return;
        if (eval.isPass() && eval.getScore() >= 8) agent.reflectionListener.onScoreAchieved(agent, eval.getScore(), round);
        else if (!eval.isShouldContinue()) agent.reflectionListener.onNoImprovement(agent, eval.getScore(), round);
    }

    static ReflectionStatus determineCompletionStatus(Agent agent, ReflectionHistory history) {
        if (history.getRounds().size() >= agent.reflectionConfig.maxRound()) return ReflectionStatus.COMPLETED_MAX_ROUNDS;
        if (history.getRounds().isEmpty()) return ReflectionStatus.COMPLETED_SUCCESS;
        var lastEval = history.getRounds().getLast().getEvaluation();
        if (lastEval.isPass() && lastEval.getScore() >= 8) return ReflectionStatus.COMPLETED_SUCCESS;
        return lastEval.isShouldContinue() ? ReflectionStatus.COMPLETED_SUCCESS : ReflectionStatus.COMPLETED_NO_IMPROVEMENT;
    }

    private ReflectionOrchestrator() {
    }
}
