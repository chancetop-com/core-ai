package ai.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author stephen
 */
class ToolCallParametersTest {

    @Test
    void test() {
        var parameters = ToolCallParameters.of(String.class);
        assertNotNull(parameters);
        assertEquals(1, parameters.size());

        var param = parameters.get(0);
        assertEquals("string", param.getName());
        assertEquals(String.class, param.getClassType());
    }

    @Test
    void testOfSingleClass() {
        var parameters = ToolCallParameters.of(TestClass1.class);

        assertNotNull(parameters);
        assertEquals(3, parameters.size());

        var nameParam = parameters.stream().filter(p -> "name".equals(p.getName())).findFirst().orElse(null);
        assertNotNull(nameParam);
        assertEquals(String.class, nameParam.getClassType());

        var ageParam = parameters.stream().filter(p -> "age".equals(p.getName())).findFirst().orElse(null);
        assertNotNull(ageParam);
        assertEquals(Integer.class, ageParam.getClassType());

        var activeParam = parameters.stream().filter(p -> "active".equals(p.getName())).findFirst().orElse(null);
        assertNotNull(activeParam);
        assertEquals(Boolean.class, activeParam.getClassType());
    }

    @Test
    void testOfMultipleClasses() {
        var parameters = ToolCallParameters.of(TestClass1.class, TestClass2.class);

        assertNotNull(parameters);
        assertEquals(6, parameters.size());

        var names = parameters.stream().map(ToolCallParameter::getName).toList();
        assertTrue(names.contains("name"));
        assertTrue(names.contains("age"));
        assertTrue(names.contains("active"));
        assertTrue(names.contains("email"));
        assertTrue(names.contains("score"));
        assertTrue(names.contains("status"));
    }

    @Test
    void testOfClassWithEnum() {
        var parameters = ToolCallParameters.of(TestClass2.class);

        var statusParam = parameters.stream().filter(p -> "status".equals(p.getName())).findFirst().orElse(null);
        assertNotNull(statusParam);
        assertEquals(String.class, statusParam.getClassType());
        assertNotNull(statusParam.getEnums());
        assertEquals(2, statusParam.getEnums().size());
        assertTrue(statusParam.getEnums().contains("ACTIVE"));
        assertTrue(statusParam.getEnums().contains("INACTIVE"));
    }

    @Test
    void testOfClassWithGenericList() {
        var parameters = ToolCallParameters.of(ComplexClass.class);

        var itemsParam = parameters.stream().filter(p -> "items".equals(p.getName())).findFirst().orElse(null);
        assertNotNull(itemsParam);
        assertEquals(List.class, itemsParam.getClassType());
        assertEquals(TestClass1.class, itemsParam.getItemType());
        assertNotNull(itemsParam.getItems());
        assertEquals(3, itemsParam.getItems().size());
    }

    @Test
    void testOfEmptyClasses() {
        var parameters = ToolCallParameters.of();

        assertNotNull(parameters);
        assertTrue(parameters.isEmpty());
    }

    @Test
    void testBasicTypesWithNames() {
        var parameters = ToolCallParameters.of(String.class, Integer.class, Boolean.class);

        assertNotNull(parameters);
        assertEquals(3, parameters.size());

        var stringParam = parameters.get(0);
        assertEquals("string", stringParam.getName());
        assertEquals(String.class, stringParam.getClassType());

        var integerParam = parameters.get(1);
        assertEquals("integer", integerParam.getName());
        assertEquals(Integer.class, integerParam.getClassType());

        var booleanParam = parameters.get(2);
        assertEquals("boolean", booleanParam.getName());
        assertEquals(Boolean.class, booleanParam.getClassType());
    }

    @Test
    void testOfWithTypeNameDescription() {
        var parameters = ToolCallParameters.of(
            String.class, "username", "The user's username",
            Integer.class, "age", "The user's age",
            Boolean.class, "active", "Whether the user is active"
        );

        assertNotNull(parameters);
        assertEquals(3, parameters.size());

        var usernameParam = parameters.get(0);
        assertEquals("username", usernameParam.getName());
        assertEquals("The user's username", usernameParam.getDescription());
        assertEquals(String.class, usernameParam.getClassType());

        var ageParam = parameters.get(1);
        assertEquals("age", ageParam.getName());
        assertEquals("The user's age", ageParam.getDescription());
        assertEquals(Integer.class, ageParam.getClassType());

        var activeParam = parameters.get(2);
        assertEquals("active", activeParam.getName());
        assertEquals("Whether the user is active", activeParam.getDescription());
        assertEquals(Boolean.class, activeParam.getClassType());
    }

    @Test
    void testOfWithTypeNameDescriptionInvalidArguments() {
        // Test with wrong number of arguments
        try {
            ToolCallParameters.of(String.class, "name");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("triplets"));
        }

        // Test with wrong type at first position
        try {
            ToolCallParameters.of("Not a class", "name", "description");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be a Class"));
        }

        // Test with wrong type at second position
        try {
            ToolCallParameters.of(String.class, 123, "description");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be a String (name)"));
        }

        // Test with wrong type at third position
        try {
            ToolCallParameters.of(String.class, "name", 456);
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be a String (description)"));
        }
    }


    enum Status {
        ACTIVE, INACTIVE
    }

    static class TestClass1 {
        String name;
        Integer age;
        Boolean active;
    }

    static class TestClass2 {
        String email;
        Double score;
        Status status;
    }

    static class ComplexClass {
        String id;
        List<TestClass1> items;
    }
}
