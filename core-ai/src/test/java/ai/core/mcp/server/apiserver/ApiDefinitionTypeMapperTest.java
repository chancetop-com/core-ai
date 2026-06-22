package ai.core.mcp.server.apiserver;

import ai.core.api.apidefinition.ApiDefinitionType;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class ApiDefinitionTypeMapperTest {

    @Test
    @SuppressWarnings("unchecked")
    void testNestedObjectAndListSurviveBodyBuild() {
        var mapper = new ApiDefinitionTypeMapper(brandProfileTypes());
        var input = Map.<String, Object>of(
                "merchant_id", "m1",
                "brand", Map.of("legalName", "L", "canonicalName", "C", "voice", Map.of("description", "warm")),
                "locations", List.of(Map.of("name", "loc1")));

        var body = mapper.buildMap(rootType(), input);

        assertEquals("m1", body.get("merchant_id"));
        // the nested object must NOT be eaten: it keeps every declared field, recursively
        var brand = assertInstanceOf(Map.class, body.get("brand"));
        assertFalse(brand.isEmpty(), "brand must not be dropped");
        assertEquals("L", brand.get("legalName"));
        assertEquals("C", brand.get("canonicalName"));
        assertEquals(Map.of("description", "warm"), brand.get("voice"));
        // the list of objects is preserved as well
        var locations = assertInstanceOf(List.class, body.get("locations"));
        assertEquals(1, locations.size());
        assertEquals("loc1", ((Map<String, Object>) locations.getFirst()).get("name"));
    }

    @Test
    void testUndeclaredKeysAreFilteredOutEverywhere() {
        var mapper = new ApiDefinitionTypeMapper(brandProfileTypes());
        var input = Map.<String, Object>of(
                "merchant_id", "m1",
                "hallucinated", "junk",
                "brand", Map.of("legalName", "L", "ghost", "x"),
                "locations", List.of(Map.of("name", "loc1", "ghost", "x")));

        var body = mapper.buildMap(rootType(), input);

        assertFalse(body.containsKey("hallucinated"));
        var brand = assertInstanceOf(Map.class, body.get("brand"));
        assertFalse(brand.containsKey("ghost"));
        var locations = assertInstanceOf(List.class, body.get("locations"));
        assertFalse(assertInstanceOf(Map.class, locations.getFirst()).containsKey("ghost"));
    }

    @Test
    void testBuildParamsMapDescendsIntoNestedObject() {
        var mapper = new ApiDefinitionTypeMapper(brandProfileTypes());
        var typed = Map.<String, AbstractMap.SimpleEntry<Object, Class<?>>>of(
                "merchant_id", new AbstractMap.SimpleEntry<>("m1", String.class),
                "brand", new AbstractMap.SimpleEntry<>(Map.of("legalName", "L"), Map.class));

        var params = mapper.buildParamsMap(rootType(), typed);

        assertEquals("m1", params.get("merchant_id").getKey());
        var brand = assertInstanceOf(Map.class, params.get("brand").getKey());
        assertTrue(brand.containsKey("legalName"), "nested object query param must not be dropped");
    }

    private ApiDefinitionType rootType() {
        return bean("CreateRequest",
                field("merchant_id", "String", null),
                field("brand", "Brand", null),
                listField("locations", "Location"));
    }

    private List<ApiDefinitionType> brandProfileTypes() {
        return List.of(
                rootType(),
                bean("Brand", field("legalName", "String", null), field("canonicalName", "String", null), field("voice", "Voice", null)),
                bean("Voice", field("description", "String", null)),
                bean("Location", field("name", "String", null)));
    }

    private ApiDefinitionType bean(String name, ApiDefinitionType.Field... fields) {
        var type = new ApiDefinitionType();
        type.name = name;
        type.type = "bean";
        type.fields = List.of(fields);
        return type;
    }

    private ApiDefinitionType.Field field(String name, String type, List<String> typeParams) {
        var field = new ApiDefinitionType.Field();
        field.name = name;
        field.type = type;
        field.typeParams = typeParams;
        return field;
    }

    private ApiDefinitionType.Field listField(String name, String elementType) {
        return field(name, "list", List.of(elementType));
    }
}
