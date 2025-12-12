package ai.core.jsonschema;

import ai.core.llm.domain.ResponseFormat;
import org.junit.jupiter.api.Test;

/**
 * @author stephen
 */
class JsonSchemaUtilTest {
    @Test
    void test() {
        var fmt = ResponseFormat.of(MenuPerformanceAnalysisEvent.class);
        assert fmt.jsonSchema != null;
    }
}
