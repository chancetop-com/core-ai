package ai.core.server.quality;

import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateResult;

/**
 * @author core-ai
 */
public record QualityRunResult(AgentQualityRun run, AgentQualityReportView report, boolean alreadyCommitted) {
    public boolean passed() {
        return run.gateResult == QualityGateResult.PASSED;
    }

    public int warnCount() {
        return report.warnCount == null ? 0 : report.warnCount;
    }

    public int blockFailCount() {
        return report.blockFailCount == null ? 0 : report.blockFailCount;
    }

    public int incompleteCount() {
        return report.incompleteCount == null ? 0 : report.incompleteCount;
    }

    public int policyRevision() {
        return run.policyRevision == null ? 0 : run.policyRevision;
    }

    public String runId() {
        return run.id;
    }
}
