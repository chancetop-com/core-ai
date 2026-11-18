package ai.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSdkCapabilityManifestTest {
    @Test
    void disabledWithoutOptIn() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics/run_query"), Map.of("version", "1.0.0"));

        assertFalse(manifest.enabled());
        assertNull(manifest.digest());
        assertEquals(List.of(), manifest.entries());
    }

    @Test
    void parsesSupportedKinds() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics/run_query", "api-tool:crm/leads/list", "llm-call:lead-summary"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"));

        assertTrue(manifest.enabled());
        assertEquals(3, manifest.entries().size());
        assertEquals(List.of("mcp", "api-tool", "llm-call"),
                manifest.entries().stream().map(SkillSdkCapabilityManifest.Entry::kind).toList());
        assertEquals("analytics/run_query", manifest.entries().getFirst().displayPath());
        assertEquals(List.of(), manifest.invalid());
        assertEquals(List.of(), manifest.unsupported());
    }

    @Test
    void recordsUnsupportedDeclarationsWithoutGrantingThem() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("subagent:research", "mcp:*", "dataset:sales"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"));

        assertTrue(manifest.enabled());
        assertEquals(List.of(), manifest.entries());
        assertEquals(3, manifest.unsupported().size());
        assertTrue(manifest.unsupported().stream().noneMatch(SkillSdkCapabilityManifest.Entry::supported));
    }

    @Test
    void groupSelectorsAreUnsupportedUntilExpansionIsImplemented() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics", "api-tool:crm", "api-tool:crm/leads"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"));

        assertEquals(List.of(), manifest.entries());
        assertEquals(3, manifest.unsupported().size());
    }

    @Test
    void summaryMarksCapabilitiesAsScriptOnly() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("api-tool:crm/leads/list", "subagent:research"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"));

        var summary = manifest.summary();

        assertTrue(summary.contains("script-only"));
        assertTrue(summary.contains("api-tool: crm/leads/list"));
        assertTrue(summary.contains("Unsupported declarations"));
        assertTrue(summary.contains("subagent:research"));
    }

    @Test
    void rejectsUnknownAndMalformedEntries() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("unknown:value", "mcp:", "api-tool:crm//list"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"));

        assertEquals(3, manifest.invalid().size());
        assertEquals(List.of(), manifest.entries());
    }

    @Test
    void deduplicatesIdenticalEntries() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics/run_query", "mcp:analytics/run_query"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1"));

        assertEquals(1, manifest.entries().size());
        assertEquals("mcp:analytics/run_query", manifest.entries().getFirst().canonical());
    }

    @Test
    void digestIsStableAndOrderIndependent() {
        var metadata = Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "1");
        var first = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics/run_query", "api-tool:crm/leads/list"), metadata);
        var second = SkillSdkCapabilityManifest.parse(
                List.of("api-tool:crm/leads/list", "mcp:analytics/run_query"), metadata);
        var changed = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics/run_query", "api-tool:crm/leads/get"), metadata);

        assertEquals(first.digest(), second.digest());
        assertNotEquals(first.digest(), changed.digest());
    }

    @Test
    void unsupportedVersionDoesNotEnableTheContract() {
        var manifest = SkillSdkCapabilityManifest.parse(
                List.of("mcp:analytics/run_query"),
                Map.of(SkillSdkCapabilityManifest.CONTRACT_VERSION_KEY, "2"));

        assertFalse(manifest.enabled());
        assertEquals("2", manifest.contractVersion());
    }
}
