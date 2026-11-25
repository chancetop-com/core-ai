package ai.core.server.selfharness;

import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.quality.QualityGateException;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.skill.quality.SkillQualityGateException;
import ai.core.server.skill.quality.SkillQualityReport;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SelfHarnessApiCallerTest {
    @Test
    void skillWriteToolsReturnActionableQualityIssues() throws Exception {
        var issue = new StoredQualityIssue();
        issue.ruleId = "SKILL.EVAL_DATASET";
        issue.status = QualityIssueStatus.FAIL;
        issue.enforcement = QualityEnforcement.BLOCK;
        issue.fieldPath = "/eval/eval.json";
        issue.issueReason = "EVAL_DATASET_NOT_PACKAGED";
        issue.suggestion = "Package eval/eval.json.";
        var report = new SkillQualityReport();
        report.gateResult = QualityGateResult.REJECTED;
        report.issues = List.of(issue);
        var rejection = SkillQualityGateException.rejected(report);
        var caller = new SelfHarnessApiCaller();
        caller.dispatcher = mock(SelfHarnessDispatcher.class);

        for (var name : List.of("create_skill", "update_skill")) {
            when(caller.dispatcher.dispatch(name, "{}", null)).thenThrow(rejection);
            var response = JsonUtil.OBJECT_MAPPER.readTree(caller.callApi(name, "{}", null));

            assertEquals(rejection.getMessage(), response.path("error").textValue());
            assertEquals("SKILL_QUALITY_REJECTED", response.path("errorCode").textValue());
            assertEquals("REJECTED", response.at("/quality_report/gate_result").textValue());
            var detail = response.at("/quality_report/issues/0");
            assertEquals("SKILL.EVAL_DATASET", detail.path("rule_id").textValue());
            assertEquals("/eval/eval.json", detail.path("field_path").textValue());
            assertEquals("EVAL_DATASET_NOT_PACKAGED", detail.path("issue_reason").textValue());
            assertEquals("Package eval/eval.json.", detail.path("suggestion").textValue());
        }
    }

    @Test
    void ordinaryFailuresKeepTheirErrorResponse() {
        var caller = new SelfHarnessApiCaller();
        caller.dispatcher = new SelfHarnessDispatcher();

        assertEquals(Map.of("error", "namespace is required"),
            JsonUtil.toMap(caller.callApi("create_skill", "{}", null)));
    }

    @Test
    void gateErrorsWithoutReportAndAgentGateErrorsSurfaceErrorCode() throws Exception {
        var caller = new SelfHarnessApiCaller();
        caller.dispatcher = mock(SelfHarnessDispatcher.class);

        when(caller.dispatcher.dispatch("update_skill", "{}", null))
            .thenThrow(SkillQualityGateException.metadataMismatch("description"));
        var mismatch = JsonUtil.OBJECT_MAPPER.readTree(caller.callApi("update_skill", "{}", null));
        assertEquals("SKILL_METADATA_MISMATCH", mismatch.path("errorCode").textValue());

        var agentReport = new AgentQualityReportView();
        agentReport.gateResult = "REJECTED";
        agentReport.blockFailCount = 1;
        when(caller.dispatcher.dispatch("publish_agent", "{}", null))
            .thenThrow(QualityGateException.rejected(agentReport));
        var agentRejected = JsonUtil.OBJECT_MAPPER.readTree(caller.callApi("publish_agent", "{}", null));
        assertEquals("AGENT_QUALITY_REJECTED", agentRejected.path("errorCode").textValue());
        assertEquals("REJECTED", agentRejected.at("/quality_report/gate_result").textValue());

        when(caller.dispatcher.dispatch("get_skill", "{}", null)).thenThrow(new NullPointerException());
        var npe = JsonUtil.OBJECT_MAPPER.readTree(caller.callApi("get_skill", "{}", null));
        assertEquals("NullPointerException", npe.path("error").textValue());
    }
}
