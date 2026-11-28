package ai.core.server.skill;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillResource;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.selfharness.SelfHarnessApiCaller;
import ai.core.server.selfharness.SelfHarnessDispatcher;
import ai.core.server.selfharness.SelfHarnessTools;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityGateException;
import ai.core.server.tool.ToolRegistryService;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(SkillTier.DOMAIN, outcome.entity().tier);
        assertEquals(QualityGateResult.PASSED, outcome.report().gateResult);
        assertNotNull(outcome.entity().qualityReceipt);
        assertEquals(outcome.report().runId, outcome.entity().qualityReceipt.runId);
        verify(store).insertIfAbsent(any());
    }

    @Test
    void publishesDomainSkillWithoutEvalWithWarnings() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        when(store.insertIfAbsent(any())).thenReturn(true);
        var service = service(store, blobStore);

        var outcome = service.publish(SkillPublishRequest.upload("u1", "Admin", "demo-skill",
            content(), Map.of("README.txt", "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(SkillTier.DOMAIN, outcome.entity().tier);
        assertEquals(QualityGateResult.PASSED, outcome.report().gateResult);
        assertTrue(outcome.report().warnCount > 0);
        assertTrue(outcome.report().issues.stream().anyMatch(issue -> "SKILL.EVAL_DATASET".equals(issue.ruleId)));
        verify(store).insertIfAbsent(any());
    }

    @Test
    void publishesThirdPartyRepoSkillUnderImportedTierWithoutExemption() {
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
        request.targetTier = SkillTier.IMPORTED;

        var outcome = service.publish(request);

        assertEquals(QualityGateResult.PASSED, outcome.report().gateResult);
        assertFalse(outcome.report().exempted);
        assertEquals(SkillSourceType.REPO, outcome.entity().sourceType);
        assertEquals(SkillTier.IMPORTED, outcome.entity().tier);
        assertTrue(outcome.report().warnCount > 0);
        verify(store).insertIfAbsent(any());
    }

    @Test
    void rejectsCoreSkillWithoutEvalDatasetWithoutWriting() {
        var store = mock(SkillStore.class);
        var blobStore = mock(SkillBlobStore.class);
        var service = service(store, blobStore);
        var request = SkillPublishRequest.upload("u1", "Admin", "demo-skill",
            content(), Map.of("README.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        request.targetTier = SkillTier.CORE;

        var error = assertThrows(SkillQualityGateException.class, () -> service.publish(request));

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
        var request = SkillPublishRequest.upload("u1", "Admin", "demo-skill",
            content(), Map.of("README.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        request.targetTier = SkillTier.CORE;

        var report = service.check(request);

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

    @Test
    void builderReturnsTheFailedRuleAndLeavesTheDownloadedPackageIntact() throws Exception {
        var tools = builderToolsForLegacySkill(SkillTier.CORE);
        var before = call(tools, "download_skill", Map.of("id", "legacy-id"));

        var failed = call(tools, "update_skill", Map.of("id", "legacy-id", "content", content(),
            "resources", List.of(Map.of("path", "scripts/enrich.py", "content", "draft change"))));

        assertEquals(before, call(tools, "download_skill", Map.of("id", "legacy-id")));
        assertEquals("SKILL_QUALITY_REJECTED", failed.path("errorCode").textValue());
        var issues = failed.at("/quality_report/issues");
        assertTrue(issues.isArray());
        assertTrue(issues.findValuesAsText("rule_id").contains("SKILL.EVAL_DATASET"));
        assertTrue(issues.findValuesAsText("field_path").contains("/eval/eval.json"));
    }

    @Test
    void builderPublishesAnUpdatedLegacySkillAndDownloadsTheExactResourceBytes() throws Exception {
        var tools = builderToolsForLegacySkill(SkillTier.DOMAIN);
        var script = "print('café')\r\n";
        var rows = List.of(
            Map.of("path", "README.txt", "keep", true),
            Map.of("path", "scripts/enrich.py", "content", script),
            Map.of("path", "eval/eval.json", "content", evalDataset()));

        var published = call(tools, "update_skill", Map.of("id", "legacy-id", "content", content(), "resources", rows));

        assertEquals(1, published.path("revision").asInt(), published::toString);
        var downloaded = call(tools, "download_skill", Map.of("id", "legacy-id"));
        assertEquals(1, downloaded.path("revision").asInt());
        assertEquals(content(), downloaded.path("content").textValue());
        var files = new LinkedHashMap<String, byte[]>();
        for (var resource : downloaded.path("resources")) {
            files.put(resource.path("path").textValue(), resource.path("content").textValue().getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(3, files.size());
        assertArrayEquals("original readme\n".getBytes(StandardCharsets.UTF_8), files.get("README.txt"));
        assertArrayEquals(script.getBytes(StandardCharsets.UTF_8), files.get("scripts/enrich.py"));
        assertArrayEquals(evalDataset().getBytes(StandardCharsets.UTF_8), files.get("eval/eval.json"));
    }

    @Test
    void builderCanCheckTheStoredSkillAndAValidDraftWithoutSaving() throws Exception {
        var tools = builderToolsForLegacySkill(SkillTier.DOMAIN);
        assertTrue(tools.findBuiltinTool("update_skill").toJsonSchema().properties.containsKey("check_only"));
        var before = call(tools, "download_skill", Map.of("id", "legacy-id"));
        var baseline = call(tools, "update_skill", Map.of("id", "legacy-id", "check_only", true));
        var draft = call(tools, "update_skill", Map.of("id", "legacy-id", "check_only", true,
            "content", content(), "resources", List.of(Map.of("path", "eval/eval.json", "content", evalDataset()))));

        assertEquals("REJECTED", baseline.at("/quality_report/gate_result").textValue());
        assertEquals("PASSED", draft.at("/quality_report/gate_result").textValue());
        assertEquals(before, call(tools, "download_skill", Map.of("id", "legacy-id")));
    }

    @Test
    void malformedCheckOnlyCannotPublishAValidDraft() throws Exception {
        var tools = builderToolsForLegacySkill(SkillTier.DOMAIN);
        var before = call(tools, "download_skill", Map.of("id", "legacy-id"));
        var response = call(tools, "update_skill", Map.of("id", "legacy-id", "check_only", "true",
            "content", content(), "resources", List.of(Map.of("path", "eval/eval.json", "content", evalDataset()))));

        assertTrue(response.hasNonNull("error"));
        assertEquals(before, call(tools, "download_skill", Map.of("id", "legacy-id")));
    }

    @Test
    void contextVariablesCannotTurnAPrecheckIntoAPublish() throws Exception {
        var tools = builderToolsForLegacySkill(SkillTier.DOMAIN);
        var before = call(tools, "download_skill", Map.of("id", "legacy-id"));
        var args = JsonUtil.toJson(Map.of("id", "legacy-id", "check_only", true, "content", content(),
            "resources", List.of(Map.of("path", "eval/eval.json", "content", evalDataset()))));
        for (var override : List.of(false, "false", "true")) {
            var context = ExecutionContext.builder().customVariable("check_only", override).build();
            var result = tools.findBuiltinTool("update_skill").execute(args, context);

            assertEquals(before, call(tools, "download_skill", Map.of("id", "legacy-id")));
            assertTrue(JsonUtil.OBJECT_MAPPER.readTree(result.getResult()).hasNonNull("error"));
        }
    }

    private ToolRegistryService builderToolsForLegacySkill(SkillTier tier) throws ReflectiveOperationException {
        var legacy = new SkillDefinition();
        legacy.id = "legacy-id";
        legacy.namespace = "fbradmin";
        legacy.name = "demo-skill";
        legacy.qualifiedName = "fbradmin/demo-skill";
        legacy.userId = "u1";
        legacy.sourceType = SkillSourceType.UPLOAD;
        legacy.tier = tier;
        legacy.content = "---\nname: demo-skill\ndescription: Old draft.\n---\n# Draft";
        var readme = new SkillResource();
        readme.path = "README.txt";
        readme.content = "original readme\n";
        var script = new SkillResource();
        script.path = "scripts/enrich.py";
        script.content = "print('old')";
        legacy.resources = List.of(readme, script);
        var persisted = new AtomicReference<>(JsonUtil.toJson(legacy));
        var store = mock(SkillStore.class);
        when(store.get(legacy.id)).thenAnswer(call -> Optional.of(JsonUtil.fromJson(SkillDefinition.class, persisted.get())));
        when(store.replaceIfRevision(any(), eq(0))).thenAnswer(call -> {
            persisted.set(JsonUtil.toJson(call.getArgument(0, SkillDefinition.class)));
            return true;
        });

        var skills = new SkillService();
        skills.skillStore = store;
        skills.publishService = service(store, new SkillBlobStore());
        var dispatcher = new SelfHarnessDispatcher();
        inject(dispatcher, "skillService", skills);
        var caller = new SelfHarnessApiCaller();
        inject(caller, "dispatcher", dispatcher);
        var registry = new ToolRegistryService();
        var tools = new SelfHarnessTools();
        inject(tools, "caller", caller);
        inject(tools, "toolRegistryService", registry);
        tools.initialize();
        return registry;
    }

    private void inject(Object target, String name, Object value) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        assertTrue(field.trySetAccessible(), name);
        field.set(target, value);
    }

    private JsonNode call(ToolRegistryService registry, String name, Map<String, ?> args) throws Exception {
        var result = registry.findBuiltinTool(name).execute(JsonUtil.toJson(args));
        return JsonUtil.OBJECT_MAPPER.readTree(result.getResult());
    }

    private SkillPublishService service(SkillStore store, SkillBlobStore blobStore) {
        var service = new SkillPublishService();
        service.skillStore = store;
        service.blobStore = blobStore;
        service.gate = new SkillQualityGate();
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
