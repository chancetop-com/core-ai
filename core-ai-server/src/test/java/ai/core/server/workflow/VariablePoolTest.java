package ai.core.server.workflow;

import org.junit.jupiter.api.Test;

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
    void rendersMissingSelectorAsEmptyString() {
        var pool = new VariablePool(Map.of(), "{}");
        assertEquals("x  y", pool.render("x {{ nodes.gone.output }} y"));
    }

    @Test
    void rendersWholeObjectSelectorAsRawJson() {
        var pool = new VariablePool(Map.of("a", "{\"x\":1}"), "{}");
        assertEquals("v={\"x\":1}", pool.render("v={{ nodes.a.output }}"));
    }
}
