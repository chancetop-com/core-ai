package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariablePoolTest {
    @Test
    void resolvesWholeNodeOutputAsRawString() {
        var pool = new VariablePool(Map.of("a", "{\"x\": 1}"), "{}");
        assertEquals("{\"x\": 1}", pool.resolve("nodes.a.output").orElseThrow());
    }

    @Test
    void navigatesIntoNodeOutputByDottedPath() {
        var pool = new VariablePool(Map.of("http1", "{\"body\": {\"userId\": \"u42\"}}"), "{}");
        assertEquals("u42", pool.resolve("nodes.http1.output.body.userId").orElseThrow());
    }

    @Test
    void resolvesRunInputViaSysAndShorthand() {
        var pool = new VariablePool(Map.of(), "{\"q\": \"hi\"}");
        assertEquals("hi", pool.resolve("sys.input.q").orElseThrow());
        assertEquals("hi", pool.resolve("input.q").orElseThrow());
    }

    @Test
    void missingSelectorResolvesEmpty() {
        var pool = new VariablePool(Map.of(), "{}");
        assertTrue(pool.resolve("nodes.nope.output").isEmpty());
        assertTrue(pool.resolve("bogus.x").isEmpty());
        assertTrue(pool.resolve("input.missing").isEmpty());
    }

    @Test
    void rendersTemplateSubstitutingSelectors() {
        var pool = new VariablePool(Map.of("a", "{\"name\": \"bob\"}"), "{\"id\": \"7\"}");
        assertEquals("hello bob (7)", pool.render("hello {{ nodes.a.output.name }} ({{ input.id }})"));
    }

    @Test
    void renderJsonEscapesValueSoUpstreamDataCannotBreakJson() {
        // upstream value contains a quote, a newline and a JSON-injection attempt
        var pool = new VariablePool(Map.of("a", "{\"v\":\"he said \\\"hi\\\"\\n\\\",\\\"admin\\\":true\"}"), "{}");
        String rendered = pool.renderJson("{\"q\":\"{{ nodes.a.output.v }}\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = JSON.fromJSON(Map.class, rendered);   // must still be valid JSON, no injected key
        assertEquals("he said \"hi\"\n\",\"admin\":true", parsed.get("q"));
        assertTrue(!parsed.containsKey("admin"));
    }

    @Test
    void plainRenderDoesNotEscape() {
        var pool = new VariablePool(Map.of("a", "{\"v\":\"a\\\"b\"}"), "{}");
        assertEquals("x=a\"b", pool.render("x={{ nodes.a.output.v }}"));   // render stays raw (non-JSON contexts)
    }

    @Test
    void rendersMissingSelectorAsEmptyString() {
        var pool = new VariablePool(Map.of(), "{}");
        assertEquals("x  y", pool.render("x {{ nodes.gone.output }} y"));
    }

    @Test
    void rendersWholeObjectSelectorAsRawJson() {
        var pool = new VariablePool(Map.of("a", "{\"x\":1}"), "{}");
        assertEquals("v={\"x\":1}", pool.render("v={{ nodes.a.output }}"));
    }

    @Test
    void resolvesNodeArtifactsWholeAndByIndex() {
        var pool = new VariablePool(Map.of(), Map.of("agent1", List.of(ref("f1", "report.pdf", "https://h/api/files/f1/content"))), "{}");
        // index path into the array reaches a single field
        assertEquals("https://h/api/files/f1/content", pool.resolve("nodes.agent1.artifacts.0.url").orElseThrow());
        assertEquals("report.pdf", pool.resolve("nodes.agent1.artifacts.0.file_name").orElseThrow());
        // whole-list selector renders as the raw JSON array
        assertTrue(pool.resolve("nodes.agent1.artifacts").orElseThrow().toString().contains("\"file_id\":\"f1\""));
    }

    @Test
    void missingArtifactsAndOutOfRangeIndexResolveEmpty() {
        var pool = new VariablePool(Map.of(), Map.of("agent1", List.of(ref("f1", "a.pdf", "u"))), "{}");
        assertTrue(pool.resolve("nodes.none.artifacts").isEmpty());
        assertTrue(pool.resolve("nodes.agent1.artifacts.9.url").isEmpty());
    }

    @Test
    void nodeWithoutArtifactsKeepsOutputAndResolvesArtifactsEmpty() {
        var pool = new VariablePool(Map.of("a", "{\"x\":1}"), "{}");   // no artifacts channel
        assertEquals("{\"x\":1}", pool.resolve("nodes.a.output").orElseThrow());
        assertTrue(pool.resolve("nodes.a.artifacts").isEmpty());
        assertTrue(pool.artifactsOf("a").isEmpty());
    }

    @Test
    void exposesArtifactsForOutputComposition() {
        var refs = List.of(ref("f1", "a.pdf", "u1"));
        var pool = new VariablePool(Map.of(), Map.of("agent1", refs), "{}");
        assertEquals(1, pool.artifactsOf("agent1").size());
        assertTrue(pool.artifactsOf("missing").isEmpty());
    }

    private static ArtifactRef ref(String fileId, String fileName, String url) {
        var artifact = new ai.core.server.domain.AgentRunArtifact();
        artifact.fileId = fileId;
        artifact.fileName = fileName;
        return ArtifactRef.of(artifact, url);
    }
}
