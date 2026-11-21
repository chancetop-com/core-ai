package ai.core.server.evaluation.service;

import java.util.List;

/**
 * @author stephen
 */
public record EvalSpecDraft(String datasetId,
                            String graderSuiteId,
                            String profile,
                            Integer repetitions,
                            Integer agentConcurrency,
                            Integer trialDeadlineSeconds,
                            Integer trialMaxTurns,
                            Integer runDeadlineSeconds,
                            String split,
                            List<String> caseIds,
                            String defaultClock) {
}
