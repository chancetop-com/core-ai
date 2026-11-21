package ai.core.server.evaluation.service;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.server.run.AgentRunStore;
import ai.core.server.run.AgentRunner;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Executes a frozen candidate through the native Agent runtime and observes its durable run record.
 * The AgentRunner pool is process-local, so an unknown completion is reported as OUTCOME_UNKNOWN rather
 * than replayed automatically.
 *
 * @author stephen
 */
public class EvalAgentRunGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvalAgentRunGateway.class);
    private static final long POLL_MILLIS = 500;
    private static final long GRACE_SECONDS = 15;

    @Inject
    AgentRunner agentRunner;
    @Inject
    AgentRunStore agentRunStore;
    @Inject
    EvalSnapshotBuilder snapshotBuilder;

    public String start(EvalBodies.CandidateSnapshotBody snapshot, String actorId, String input,
                        Map<String, String> variables, int maxTurns, int timeoutSeconds) {
        var definition = snapshotBuilder.detachedDefinition(snapshot, actorId, maxTurns, timeoutSeconds);
        return agentRunner.runAs(definition, input, TriggerType.MANUAL, actorId,
            variables == null || variables.isEmpty() ? Map.of() : variables);
    }

    public Outcome await(String agentRunId, ZonedDateTime deadline) {
        var hardDeadline = deadline.plusSeconds(GRACE_SECONDS);
        while (ZonedDateTime.now().isBefore(hardDeadline)) {
            var run = agentRunStore.get(agentRunId).orElse(null);
            if (run == null) return new Outcome(EvalEnums.ExecutionResult.PLATFORM_ERROR, null, null, "agent run record disappeared");
            if (terminal(run.status)) return toOutcome(run);
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(EvalEnums.ExecutionResult.OUTCOME_UNKNOWN, null, null, "evaluation worker interrupted");
            }
        }
        var latest = agentRunStore.get(agentRunId).orElse(null);
        if (latest != null && terminal(latest.status)) return toOutcome(latest);
        agentRunner.cancel(agentRunId);
        return new Outcome(EvalEnums.ExecutionResult.OUTCOME_UNKNOWN, null, null,
            "runtime completion was not confirmed before the trial deadline");
    }

    public void cancel(String agentRunId) {
        if (agentRunId == null) return;
        try {
            agentRunner.cancel(agentRunId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to cancel agent run for evaluation, agentRunId={}", agentRunId, e);
        }
    }

    private boolean terminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.TIMEOUT
            || status == RunStatus.CANCELLED || status == RunStatus.REJECTED;
    }

    Outcome toOutcome(AgentRun run) {
        var usage = new EvalBodies.TrialUsage();
        if (run.tokenUsage != null) {
            usage.inputTokens = toInt(run.tokenUsage.input);
            usage.outputTokens = toInt(run.tokenUsage.output);
        }
        var usageJson = usage.inputTokens == null && usage.outputTokens == null ? null : JsonUtil.toJson(usage);
        var error = run.error;
        if (run.status == RunStatus.COMPLETED) {
            if (error != null && error.startsWith("max turns reached")) {
                return new Outcome(EvalEnums.ExecutionResult.AGENT_FAILED, run.output, usageJson, error);
            }
            return new Outcome(EvalEnums.ExecutionResult.SUCCEEDED, run.output, usageJson, null);
        }
        if (run.status == RunStatus.TIMEOUT) {
            return new Outcome(EvalEnums.ExecutionResult.TIMEOUT, null, usageJson, error);
        }
        if (run.status == RunStatus.CANCELLED) {
            return new Outcome(EvalEnums.ExecutionResult.CANCELLED, null, usageJson, error);
        }
        if (run.status == RunStatus.REJECTED) {
            return new Outcome(EvalEnums.ExecutionResult.PLATFORM_ERROR, null, usageJson, error);
        }
        if (error != null && error.contains("CancellationException")) {
            return new Outcome(EvalEnums.ExecutionResult.CANCELLED, null, usageJson, error);
        }
        return new Outcome(EvalEnums.ExecutionResult.AGENT_FAILED, null, usageJson, error);
    }

    private Integer toInt(Long value) {
        if (value == null) return null;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    public record Outcome(EvalEnums.ExecutionResult result, String output, String usageJson, String error) {
    }
}
