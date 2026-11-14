package ai.core.server.quality;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionException;
import ai.core.decision.DecisionOptions;
import ai.core.decision.DecisionProvider;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionUsage;
import core.framework.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * The strict decision adapter used only by the publish gate. The general decide tool keeps its tolerant
 * parser; a publish decision must fail closed instead of treating a malformed answer as "no problem".
 *
 * @author core-ai
 */
public class QualityDecisionGateway {
    private static final double DISTRIBUTION_TOLERANCE = 0.01;

    @Inject
    DecisionProvider decisionProvider;

    /** Wired by QualityGateModule; used only for the pre-commit drift recheck, never for a second verdict. */
    private ai.core.server.gateway.GatewayRoutingEngine routingEngine;

    public void setRoutingEngine(ai.core.server.gateway.GatewayRoutingEngine routingEngine) {
        this.routingEngine = routingEngine;
    }

    public String currentRouteFingerprint(String modelAlias) {
        if (routingEngine == null) return null;
        try {
            var route = routingEngine.route(modelAlias, ai.core.server.gateway.GatewayEndpointType.DECISION);
            return route.fingerprint(modelAlias);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public ValidatedDecision decide(String modelAlias, String state, List<DecisionQuestion> questions,
                                    String expectedRouteFingerprint, QualityBudget budget) {
        if (state == null || state.isBlank()) {
            throw new QualityDecisionException(QualityDecisionException.Kind.PROTOCOL,
                "decision state is empty", false, null);
        }
        if (!budget.canDecide(questions.size(), state.length())) {
            throw new QualityDecisionException(QualityDecisionException.Kind.BUDGET,
                "decision budget exhausted", true, null);
        }
        var request = new DecisionRequest(modelAlias, state, questions);
        var options = DecisionOptions.timeout(budget.callTimeout());
        DecisionResponse response;
        try {
            response = decisionProvider.decide(request, options);
        } catch (DecisionException e) {
            var retryable = e.statusCode() == 429 || e.statusCode() >= 500;
            throw new QualityDecisionException(QualityDecisionException.Kind.PROVIDER,
                "decision provider failed: " + e.getMessage(), retryable, e);
        } catch (QualityDecisionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new QualityDecisionException(QualityDecisionException.Kind.PROVIDER,
                "decision provider failed: " + e.getMessage(), true, e);
        }
        budget.consume(questions.size(), state.length());
        validate(response, questions, expectedRouteFingerprint);
        return new ValidatedDecision(response.answers(), questions, response.route(),
            response.route().upstreamModel(), tokens(response.usage(), true), tokens(response.usage(), false));
    }

    private long tokens(DecisionUsage usage, boolean input) {
        if (usage == null) return 0L;
        return input ? usage.inputTokens() : usage.outputTokens();
    }

    private void validate(DecisionResponse response, List<DecisionQuestion> questions, String expectedRouteFingerprint) {
        var allowed = new LinkedHashMap<String, DecisionQuestion>();
        for (var question : questions) allowed.put(question.name(), question);

        for (var name : response.answers().keySet()) {
            if (!allowed.containsKey(name)) {
                throw protocol("decision response contains an unknown answer: " + name);
            }
        }
        for (var question : questions) {
            var answer = response.answers().get(question.name());
            if (answer == null) throw protocol("decision response is missing answer: " + question.name());
            validateAnswer(question, answer);
        }

        var route = response.route();
        if (!route.isComplete()) {
            throw new QualityDecisionException(QualityDecisionException.Kind.ROUTE_DRIFT,
                "decision route metadata is missing", false, null);
        }
        if (expectedRouteFingerprint != null && !expectedRouteFingerprint.isBlank()
            && !expectedRouteFingerprint.equals(route.routeFingerprint())) {
            throw new QualityDecisionException(QualityDecisionException.Kind.ROUTE_DRIFT,
                "decision route changed: expected " + expectedRouteFingerprint + " but got " + route.routeFingerprint(),
                false, null);
        }
    }

    private void validateAnswer(DecisionQuestion question, DecisionAnswer answer) {
        if (answer.type() == null) throw protocol("answer type is missing: " + question.name());
        if (answer.type() != question.type()) {
            throw protocol("answer type mismatch: " + question.name() + ", expected " + question.type()
                + " but got " + answer.type());
        }
        switch (question.type()) {
            case NOUL -> {
                requireProbability(question.name(), answer.probability());
            }
            case CHOICE -> {
                if (answer.choice() == null || !question.criteria().containsKey(answer.choice())) {
                    throw protocol("answer choice is not in the criteria allowlist: " + question.name());
                }
                var winnerProbability = answer.probability();
                if (winnerProbability == null && answer.probabilities() != null) {
                    winnerProbability = answer.probabilities().get(answer.choice());
                }
                requireProbability(question.name(), winnerProbability);
                validateDistribution(question, answer);
            }
            case SCORE -> {
                if (answer.score() == null || !Double.isFinite(answer.score())) {
                    throw protocol("answer score is missing or not finite: " + question.name());
                }
                if (answer.score() < 0 || answer.score() > question.levels().size() - 1) {
                    throw protocol("answer score is out of range: " + question.name() + ", score=" + answer.score());
                }
            }
            default -> throw protocol("unsupported answer type: " + question.type());
        }
        if (answer.confidence() != null && (!Double.isFinite(answer.confidence())
            || answer.confidence() < 0 || answer.confidence() > 1)) {
            throw protocol("answer confidence is out of range: " + question.name());
        }
    }

    private void validateDistribution(DecisionQuestion question, DecisionAnswer answer) {
        var probabilities = answer.probabilities();
        if (probabilities == null || probabilities.isEmpty()) return;
        double sum = 0;
        for (var entry : probabilities.entrySet()) {
            if (!question.criteria().containsKey(entry.getKey())) {
                throw protocol("answer distribution contains a label outside the criteria allowlist: "
                    + question.name() + "/" + entry.getKey());
            }
            requireProbability(question.name(), entry.getValue());
            sum += entry.getValue();
        }
        if (Math.abs(sum - 1.0) > DISTRIBUTION_TOLERANCE) {
            throw protocol("answer distribution does not sum to 1: " + question.name() + ", sum=" + sum);
        }
    }

    private void requireProbability(String name, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw protocol("answer probability is missing or out of range: " + name);
        }
    }

    private QualityDecisionException protocol(String message) {
        return new QualityDecisionException(QualityDecisionException.Kind.PROTOCOL, message, false, null);
    }

}



