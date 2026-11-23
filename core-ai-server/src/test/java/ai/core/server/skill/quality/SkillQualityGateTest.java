package ai.core.server.skill.quality;

import ai.core.server.domain.SkillSourceType;
import ai.core.server.quality.QualityExemptions;
import ai.core.server.quality.domain.QualityGateResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
class SkillQualityGateTest {
    @Test
    void validSkillPasses() {
        var result = new SkillQualityGate().evaluate(candidate(true));

        assertEquals(QualityGateResult.PASSED, result.gateResult(), result.issues().toString());
        assertTrue(result.issues().stream().noneMatch(issue -> issue.status == ai.core.server.quality.domain.QualityIssueStatus.FAIL));
    }

    @Test
    void missingEvalDatasetRejects() {
        var result = new SkillQualityGate().evaluate(candidate(false));

        assertEquals(QualityGateResult.REJECTED, result.gateResult());
        assertTrue(result.issues().stream().anyMatch(issue -> "EVAL_DATASET_NOT_PACKAGED".equals(issue.issueReason)));
    }

    @Test
    void missingWorkflowSectionRejects() {
        var candidate = candidate(true, """
            ---
            name: demo-skill
            description: "Do demo. Use when asked. Scope: demo only."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo
            ---
            ## Output
            out
            ## Error handling
            retry
            ## Quality assurance
            run eval
            """);

        var result = new SkillQualityGate().evaluate(candidate);

        assertTrue(result.issues().stream().anyMatch(issue -> "MISSING_SECTION".equals(issue.issueReason)
            && issue.message.contains("Workflow")));
    }

    @Test
    void thirdPartyRepoImportSkipsTheGate() {
        var candidate = new SkillPublishCandidateBuilder().build("demo", "demo-skill", """
            ---
            name: demo-skill
            description: Imported from an external repository.
            ---
            # Demo
            """, Map.of(), SkillSourceType.REPO, null);

        var result = new SkillQualityGate().evaluate(candidate);

        assertEquals(QualityGateResult.PASSED, result.gateResult());
        assertTrue(result.exempted());
        assertEquals(QualityExemptions.THIRD_PARTY_REPO_REASON, result.exemptionReason());
    }

    @Test
    void uploadedSkillWithTheSameGapsStillRejects() {
        var candidate = new SkillPublishCandidateBuilder().build("demo", "demo-skill", """
            ---
            name: demo-skill
            description: Uploaded by an author.
            ---
            # Demo
            """, Map.of(), SkillSourceType.UPLOAD, null);

        var result = new SkillQualityGate().evaluate(candidate);

        assertEquals(QualityGateResult.REJECTED, result.gateResult());
    }

    @Test
    void ruleCatalogMatchesTheRulesTheGateRuns() {
        var catalog = SkillQualityGate.ruleCatalog();

        assertEquals(10, catalog.size());
        assertEquals(SkillQualityGate.ruleKeys(), catalog.stream().map(SkillRuleMeta::key).toList());
        assertTrue(catalog.stream().allMatch(meta -> meta.name() != null && meta.requirement() != null
            && meta.category() != null && meta.enforcement() != null && meta.revision() > 0));
    }

    private SkillPublishCandidate candidate(boolean withEval) {
        return candidate(withEval, skillContent());
    }

    private SkillPublishCandidate candidate(boolean withEval, String content) {
        var resources = new LinkedHashMap<String, byte[]>();
        resources.put("README.txt", "demo".getBytes(StandardCharsets.UTF_8));
        if (withEval) resources.put("eval/eval.json", evalDataset().getBytes(StandardCharsets.UTF_8));
        return new SkillPublishCandidateBuilder().build("demo", "demo-skill", content, resources, null);
    }

    private String skillContent() {
        return """
            ---
            name: demo-skill
            description: "Do demo things. Use when the user asks for a demo. Scope: demo inputs only; not for production."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo,test
            ---
            # Demo skill

            ## Workflow
            1. Read the request.
            2. Produce the demo artifact.

            ## Decision making
            If the request is outside the demo scope, refuse. Otherwise continue.

            ## Output specification
            Return one Markdown file named demo.md.

            ## Error handling
            If the input is missing, ask the user; if the tool fails, retry once and then report the failure.

            ## Quality assurance
            Run `python eval/run.py --dataset eval/eval.json`; all seven dimensions must report pass.
            """;
    }

    private String evalDataset() {
        var cases = List.of(
            evalCase("trigger-001", "trigger_accuracy", "{\"triggered\":true}", "run demo"),
            evalCase("trigger-002", "trigger_accuracy", "{\"triggered\":false}", "unrelated question, no demo"),
            evalCase("scope-001", "scope_accuracy",
                "{\"in_scope\":true,\"expected_behavior\":\"handle\"}", "demo this input"),
            evalCase("tool-001", "tool_accuracy", "{\"no_tools\":true}", "answer without tools"),
            evalCase("param-001", "parameter_accuracy",
                "{\"tool\":\"read_file\",\"arguments\":{},\"match\":\"schema\"}", "read the input"),
            evalCase("instr-001", "instruction_following", "{\"rules\":[\"keep one file\"]}", "follow the rule"),
            evalCase("output-001", "output_quality", "{\"outputs\":[]}", "produce demo.md"),
            evalCase("robust-001", "robustness",
                "{\"failure_mode\":\"missing input\",\"expected_behavior\":\"ask_user\"}", "input is absent")
        );
        return "{\"schema_version\":1,\"skill\":\"demo-skill\",\"dataset_version\":\"1.0.0\",\"cases\":["
            + String.join(",", cases) + "]}";
    }

    private String evalCase(String id, String dimension, String expected, String request) {
        return "{\"id\":\"" + id + "\",\"dimension\":\"" + dimension + "\","
            + "\"input\":{\"user_request\":\"" + request + "\"},"
            + "\"expected\":" + expected + ",\"assertions\":[{\"kind\":\"no_crash\"}]}";
    }
}
