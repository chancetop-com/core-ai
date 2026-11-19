package ai.core.server.quality;

import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRunStatus;
import ai.core.server.quality.domain.StoredQualityIssue;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityReportMapperTest {
    @Test
    void runViewSummarizesIssuesWithoutShadowNoise() {
        var run = new AgentQualityRun();
        run.id = "run-1";
        run.agentId = "agent-1";
        run.mode = QualityGateMode.PRECHECK;
        run.runStatus = QualityRunStatus.COMPLETED;
        run.gateResult = QualityGateResult.REJECTED;
        run.policyRevision = 5;
        run.definitionRevision = 3;
        run.checksComplete = Boolean.TRUE;
        run.exempted = Boolean.TRUE;
        run.exemptionReason = "built-in system agent";
        run.createdAt = ZonedDateTime.now();
        run.issues = List.of(
            issue(QualityEnforcement.WARN, QualityIssueStatus.FAIL),
            issue(QualityEnforcement.WARN, QualityIssueStatus.PASS),
            issue(QualityEnforcement.BLOCK, QualityIssueStatus.FAIL),
            issue(QualityEnforcement.BLOCK, QualityIssueStatus.ERROR),
            issue(QualityEnforcement.SHADOW, QualityIssueStatus.FAIL));

        var view = QualityReportMapper.runView(run);

        assertThat(view.runId).isEqualTo("run-1");
        assertThat(view.mode).isEqualTo("PRECHECK");
        assertThat(view.runStatus).isEqualTo("COMPLETED");
        assertThat(view.gateResult).isEqualTo("REJECTED");
        assertThat(view.policyRevision).isEqualTo(5);
        assertThat(view.definitionRevision).isEqualTo(3);
        assertThat(view.warnCount).isEqualTo(1);
        assertThat(view.blockFailCount).isEqualTo(1);
        assertThat(view.incompleteCount).isEqualTo(1);
        assertThat(view.exempted).isTrue();
        assertThat(view.exemptionReason).isEqualTo("built-in system agent");
    }

    private StoredQualityIssue issue(QualityEnforcement enforcement, QualityIssueStatus status) {
        var issue = new StoredQualityIssue();
        issue.enforcement = enforcement;
        issue.status = status;
        return issue;
    }
}
