package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.quality.QualityExemptions;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.skill.quality.SkillQualityGateException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings({"NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION", "MRC_METHOD_RETURNS_CONSTANT"})
class SkillPublishServiceTest {
    @Test
    void publishesNewSkillWithReceipt() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        when(store.insertIfAbsent(any())).thenReturn(true);
        var service = service(store, blobStore);

        var outcome = service.publish(SkillPublishRequest.upload("u1", "Admin", "demo-skill",
            content(), resources()));

        assertEquals(1, outcome.entity().revision);
        assertEquals(QualityGateResult.PASSED, outcome.report().gateResult);
        assertNotNull(outcome.entity().qualityReceipt);
        assertEquals(outcome.report().runId, outcome.entity().qualityReceipt.runId);
        verify(store).insertIfAbsent(any());
    }

    @Test
    void publishesThirdPartyRepoSkillWithoutQualityGate() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        when(store.insertIfAbsent(any())).thenReturn(true);
        var service = service(store, blobStore);
        var request = SkillPublishRequest.upload("marketplace", "AgriciDaniel", "ads-tiktok", """
            ---
            name: ads-tiktok
            description: Imported from the claude-ads repository.
            ---
            # TikTok Ads Audit
            """, Map.of());
        request.sourceType = SkillSourceType.REPO;

        var outcome = service.publish(request);

        assertEquals(QualityGateResult.PASSED, outcome.report().gateResult);
        assertTrue(outcome.report().exempted);
        assertEquals(SkillSourceType.REPO, outcome.entity().sourceType);
        assertEquals(QualityExemptions.THIRD_PARTY_REPO_REASON, outcome.entity().qualityReceipt.exemptionReason);
        verify(store).insertIfAbsent(any());
    }

    @Test
    void rejectsInvalidPackageWithoutWriting() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        var service = service(store, blobStore);

        var error = assertThrows(SkillQualityGateException.class,
            () -> service.publish(SkillPublishRequest.upload("u1", "Admin", "demo-skill",
                content(), Map.of("README.txt", "hello".getBytes(StandardCharsets.UTF_8)))));

        assertEquals(SkillQualityGateException.REJECTED, error.errorCode());
        var extra = error.extraJson();
        assertNotNull(extra);
        assertTrue(extra.contains("\"quality_report\":"));
        assertTrue(extra.contains("\"gate_result\":\"REJECTED\""));
        assertTrue(extra.contains("\"rule_id\":\"SKILL.EVAL_DATASET\""));
        assertTrue(extra.contains("\"message\":"));
        verify(store, never()).insertIfAbsent(any());
        verify(blobStore, never()).put(any(), any(), any());
    }

    @Test
    void checkReturnsRejectedReportWithoutThrowingOrWriting() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        var service = service(store, blobStore);

        var report = service.check(SkillPublishRequest.upload("u1", "Admin", "demo-skill",
            content(), Map.of("README.txt", "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(QualityGateResult.REJECTED, report.gateResult);
        assertTrue(report.issues.stream().anyMatch(issue -> "SKILL.EVAL_DATASET".equals(issue.ruleId)));
        verify(store, never()).insertIfAbsent(any());
        verify(store, never()).replace(any());
        verify(store, never()).replaceIfRevision(any(), anyInt());
        verify(blobStore, never()).put(anyString(), anyString(), any(byte[].class));
        verify(blobStore, never()).deleteReplaced(any(), any());
    }

    @Test
    void checkReturnsPassedReportForValidPackageWithoutWriting() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        var service = service(store, blobStore);

        var report = service.check(SkillPublishRequest.upload("u1", "Admin", "demo-skill",
            content(), resources()));

        assertEquals(QualityGateResult.PASSED, report.gateResult);
        verify(store, never()).insertIfAbsent(any());
        verify(store, never()).replace(any());
        verify(store, never()).replaceIfRevision(any(), anyInt());
    }

    @Test
    void reportsStaleRevisionWhenCasLoses() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        var existing = new SkillDefinition();
        existing.id = "1";
        existing.namespace = "Admin";
        existing.name = "demo-skill";
        existing.directoryName = "demo-skill";
        existing.qualifiedName = "Admin/demo-skill";
        existing.content = content();
        existing.revision = 2;
        existing.sourceType = SkillSourceType.UPLOAD;
        existing.userId = "u1";
        when(store.replaceIfRevision(any(), eq(2))).thenReturn(false);
        var service = service(store, blobStore);

        var request = SkillPublishRequest.upload("u1", "Admin", "demo-skill", content(), resources());
        request.existing = existing;
        var error = assertThrows(SkillQualityGateException.class, () -> service.publish(request));

        assertEquals(SkillQualityGateException.STALE, error.errorCode());
        verify(store, never()).insertIfAbsent(any());
    }

    private SkillPublishService service(SkillStore store, SkillBlobStore blobStore) {
        var service = new SkillPublishService();
        service.skillStore = store;
        service.blobStore = blobStore;
        service.gate = new ai.core.server.skill.quality.SkillQualityGate();
        return service;
    }

    private Map<String, byte[]> resources() {
        var resources = new LinkedHashMap<String, byte[]>();
        resources.put("README.txt", "hello".getBytes(StandardCharsets.UTF_8));
        resources.put("eval/eval.json", evalDataset().getBytes(StandardCharsets.UTF_8));
        return resources;
    }

    private String content() {
        return """
            ---
            name: demo-skill
            description: "Do demo things. Use when asked for a demo. Scope: demo inputs only; not for production."
            metadata:
              version: 1.0.0
              author: core-ai
              tags: demo
            ---
            ## Workflow
            1. Read.
            2. Write.
            ## Decision making
            If out of scope, refuse.
            ## Output specification
            One demo.md.
            ## Error handling
            Ask or retry once.
            ## Quality assurance
            Run eval.
            """;
    }

    private String evalDataset() {
        var cases = List.of(
            evalCase("trigger-001", "trigger_accuracy", "{\"triggered\":true}"),
            evalCase("trigger-002", "trigger_accuracy", "{\"triggered\":false}"),
            evalCase("scope-001", "scope_accuracy",
                "{\"in_scope\":true,\"expected_behavior\":\"handle\"}"),
            evalCase("tool-001", "tool_accuracy", "{\"no_tools\":true}"),
            evalCase("param-001", "parameter_accuracy",
                "{\"tool\":\"read_file\",\"arguments\":{},\"match\":\"schema\"}"),
            evalCase("instr-001", "instruction_following", "{\"rules\":[\"one file\"]}"),
            evalCase("output-001", "output_quality", "{\"outputs\":[]}"),
            evalCase("robust-001", "robustness",
                "{\"failure_mode\":\"missing input\",\"expected_behavior\":\"ask_user\"}")
        );
        return "{\"schema_version\":1,\"skill\":\"demo-skill\",\"dataset_version\":\"1.0.0\",\"cases\":["
            + String.join(",", cases) + "]}";
    }

    private String evalCase(String id, String dimension, String expected) {
        return "{\"id\":\"" + id + "\",\"dimension\":\"" + dimension + "\","
            + "\"input\":{\"user_request\":\"run the demo\"},"
            + "\"expected\":" + expected + ",\"assertions\":[{\"kind\":\"no_crash\"}]}";
    }
}
