package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;

/**
 * The publish path depends on this contract, not on the concrete gate. Tests can provide a permissive
 * implementation; production binds {@link PublishQualityGateService}.
 *
 * @author core-ai
 */
public interface PublishQualityGate {
    PublishCandidate buildCandidate(AgentDefinition entity);

    QualityRunResult evaluate(AgentDefinition entity, PublishCandidate candidate, String userId,
                              QualityGateMode mode, String idempotencyKey, boolean enforce);

    QualityRunResult precheck(AgentDefinition entity, String userId, String idempotencyKey);

    void recheck(PublishCandidate candidate, AgentQualityRun run);

    void recordPublication(AgentQualityRun run, PublishCandidate candidate, String userId);

    ai.core.api.server.agent.AgentQualityReportView report(String agentId, String runId);
}
