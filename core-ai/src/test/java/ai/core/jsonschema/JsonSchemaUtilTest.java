package ai.core.jsonschema;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.ResponseFormat;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.function.Functions;
import ai.core.tool.tools.WriteTodosTool;
import ai.core.utils.JsonSchemaUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class JsonSchemaUtilTest {
    @Test
    void test() {
        var fmt = ResponseFormat.of(MenuPerformanceAnalysisEvent.class);
        assertNotNull(fmt.jsonSchema);
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
    void emptyEnumShouldBeOmitted() {
        var param = ToolCallParameter.builder()
            .name("todos")
            .classType(List.class)
            .itemType(String.class)
            .enums(List.of())
            .required(Boolean.TRUE)
            .build();

        var schema = JsonSchemaUtil.toJsonSchema(List.of(param));

        assertNull(schema.properties.get("todos").enums);
    }

    @Test
    void writeTodosSchemaShouldNotContainEmptyEnum() {
        var tool = Functions.from(new WriteTodosTool(), "writeTodos").getFirst();
        var todos = tool.toJsonSchema().properties.get("todos");

        assertNotNull(todos);
        assertEquals(JsonSchema.PropertyType.ARRAY, todos.type);
        assertNull(todos.enums);
    }

    @Test
    void nestedObjectFromItemsShouldGenerateProperties() {
        // Simulates how ApiDefinitionTypeSchemaBuilder builds nested objects
        // by setting classType=Map.class but using items for nested fields
        var nestedParams = List.of(
            ToolCallParameter.builder().name("nestedField").classType(String.class).required(Boolean.TRUE).build()
        );

        var rootParam = ToolCallParameter.builder()
            .name("nestedObject")
            .classType(Map.class)  // Simulates ApiDefinitionTypeSchemaBuilder behavior
            .required(Boolean.TRUE)
            .items(nestedParams)
            .build();

        var schema = JsonSchemaUtil.toJsonSchema(java.util.Collections.singletonList(rootParam));

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
    void duplicateRequiredParameterNamesShouldBeDeduplicated() {
        var params = List.of(
            ToolCallParameter.builder().name("id").classType(String.class).required(Boolean.TRUE).build(),
            ToolCallParameter.builder().name("id").classType(String.class).required(Boolean.TRUE).build()
        );

        var schema = JsonSchemaUtil.toJsonSchema(params);

        assertEquals(List.of("id"), schema.required);
        assertEquals(1, schema.properties.size());
        assertTrue(schema.properties.containsKey("id"));
    }

    @Test
    void nestedArrayWithObjectsFromItemsShouldGenerateProperties() {
        // Simulates how ApiDefinitionTypeSchemaBuilder builds List<SomeObject>
        // by setting classType=List.class and using items for nested fields
        var nestedParams = List.of(
            ToolCallParameter.builder().name("name").classType(String.class).required(Boolean.TRUE).build(),
            ToolCallParameter.builder().name("value").classType(Integer.class).required(Boolean.FALSE).build()
        );

        var rootParam = ToolCallParameter.builder()
            .name("items")
            .classType(List.class)
            .required(Boolean.TRUE)
            .items(nestedParams)
            .build();

        var schema = JsonSchemaUtil.toJsonSchema(java.util.Collections.singletonList(rootParam));

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
