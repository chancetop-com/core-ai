package ai.core.jsonschema;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.ResponseFormat;
import ai.core.tool.ToolCallParameter;
import ai.core.utils.JsonSchemaUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void nestedObjectFromItemsShouldGenerateProperties() {
        // Simulates how ApiDefinitionTypeSchemaBuilder builds nested objects
        // by setting classType=Map.class but using items for nested fields
        var nestedParams = List.of(
            ToolCallParameter.builder().name("nestedField").classType(String.class).required(true).build()
        );

        var rootParam = ToolCallParameter.builder()
            .name("nestedObject")
            .classType(Map.class)  // Simulates ApiDefinitionTypeSchemaBuilder behavior
            .required(true)
            .items(nestedParams)
            .build();

        var schema = JsonSchemaUtil.toJsonSchema(java.util.Arrays.asList(rootParam));

        assertNotNull(schema.properties);
        var nestedObjectProperty = schema.properties.get("nestedObject");
        assertNotNull(nestedObjectProperty);
        assertEquals(JsonSchema.PropertyType.OBJECT, nestedObjectProperty.type);

        // Key assertion: nested properties should be generated from items
        assertNotNull(nestedObjectProperty.properties, "Nested properties should be generated from items");
        assertTrue(nestedObjectProperty.properties.containsKey("nestedField"), "nestedField should exist in nested properties");
        assertEquals(JsonSchema.PropertyType.STRING, nestedObjectProperty.properties.get("nestedField").type);

        // Required fields should also be propagated
        assertNotNull(nestedObjectProperty.required);
        assertTrue(nestedObjectProperty.required.contains("nestedField"), "nestedField should be in required list");
    }

    @Test
    void nestedArrayWithObjectsFromItemsShouldGenerateProperties() {
        // Simulates how ApiDefinitionTypeSchemaBuilder builds List<SomeObject>
        // by setting classType=List.class and using items for nested fields
        var nestedParams = List.of(
            ToolCallParameter.builder().name("name").classType(String.class).required(true).build(),
            ToolCallParameter.builder().name("value").classType(Integer.class).required(false).build()
        );

        var rootParam = ToolCallParameter.builder()
            .name("items")
            .classType(List.class)
            .required(true)
            .items(nestedParams)
            .build();

        var schema = JsonSchemaUtil.toJsonSchema(java.util.Arrays.asList(rootParam));

        assertNotNull(schema.properties);
        var itemsProperty = schema.properties.get("items");
        assertNotNull(itemsProperty);
        assertEquals(JsonSchema.PropertyType.ARRAY, itemsProperty.type);
        assertNotNull(itemsProperty.items, "Array items schema should be generated from items");

        // Array item should have nested properties
        var itemSchema = itemsProperty.items;
        assertEquals(JsonSchema.PropertyType.OBJECT, itemSchema.type);
        assertNotNull(itemSchema.properties, "Nested properties should be generated from items");
        assertTrue(itemSchema.properties.containsKey("name"), "name should exist in item properties");
        assertTrue(itemSchema.properties.containsKey("value"), "value should exist in item properties");
    }
}
