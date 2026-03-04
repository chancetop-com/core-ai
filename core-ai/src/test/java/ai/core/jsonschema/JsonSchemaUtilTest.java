package ai.core.jsonschema;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.ResponseFormat;
import ai.core.utils.JsonSchemaUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class JsonSchemaUtilTest {
    @Test
    void test() {
        var fmt = ResponseFormat.of(MenuPerformanceAnalysisEvent.class);
        assert fmt.jsonSchema != null;
    }

    @Test
    void enumArrayFieldShouldHaveEnumValues() {
        var schema = JsonSchemaUtil.toJsonSchema(EnumTestModel.class);

        var tagsProperty = schema.properties.get("tags");
        assertNotNull(tagsProperty);
        assertEquals(JsonSchema.PropertyType.ARRAY, tagsProperty.type);
        assertNotNull(tagsProperty.items);
        assertEquals(JsonSchema.PropertyType.STRING, tagsProperty.items.type);
        assertNotNull(tagsProperty.items.enums);
        assertEquals(3, tagsProperty.items.enums.size());
        assertTrue(tagsProperty.items.enums.contains("A"));
        assertTrue(tagsProperty.items.enums.contains("B"));
        assertTrue(tagsProperty.items.enums.contains("C"));

        var statusProperty = schema.properties.get("status");
        assertNotNull(statusProperty);
        assertEquals(JsonSchema.PropertyType.STRING, statusProperty.type);
        assertNotNull(statusProperty.enums);
        assertTrue(statusProperty.enums.contains("A"));
    }
}
