package ai.core.server.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * @author stephen
 */
class EvalHashingTest {
    @Test
    void canonicalJsonIgnoresObjectKeyOrderButPreservesArrayOrder() {
        var first = "{\"b\":1,\"a\":2}";
        var second = "{\"a\":2,\"b\":1}";
        assertEquals(EvalHashing.hashJson(first), EvalHashing.hashJson(second));
        assertNotEquals(EvalHashing.hashJson("[1,2]"), EvalHashing.hashJson("[2,1]"));
    }

    @Test
    void canonicalJsonNormalizesEquivalentNumbers() {
        assertEquals(EvalHashing.hashJson("{\"v\":1.0}"), EvalHashing.hashJson("{\"v\":1}"));
    }

    @Test
    void idSetHashIsOrderIndependent() {
        assertEquals(EvalHashing.hashIdSet(java.util.List.of("b", "a")), EvalHashing.hashIdSet(java.util.List.of("a", "b")));
    }
}
