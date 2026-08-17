package ai.core.server.seoops;

import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeoExecutionSpecHasherTest {
    private final SeoExecutionSpecHasher hasher = new SeoExecutionSpecHasher();

    @Test
    void objectKeyOrderDoesNotChangeCanonicalHash() {
        assertEquals(hasher.hash("{\"b\":2,\"a\":1}"), hasher.hash("{\"a\":1,\"b\":2}"));
        assertEquals("{\"a\":1,\"b\":{\"c\":2}}", hasher.canonicalize("{ \"b\": {\"c\": 2}, \"a\": 1 }"));
    }

    @Test
    void arrayOrderChangesHash() {
        assertNotEquals(hasher.hash("{\"x\":[1,2]}"), hasher.hash("{\"x\":[2,1]}"));
    }

    @Test
    void malformedExecutionSpecFailsClosed() {
        assertThrows(BadRequestException.class, () -> hasher.hash("{not-json}"));
    }
}
