package ai.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var parameters = ToolCallParameters.of(new Class<?>[0]);

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
    void testParamSpecWithRequired() {
        var parameters = ToolCallParameters.of(
            ToolCallParameters.ParamSpec.of(String.class, "username", "User name").required(),
            ToolCallParameters.ParamSpec.of(Integer.class, "age", "User age").optional(),
            ToolCallParameters.ParamSpec.of(String.class, "bio", "User bio")  // default optional
        );

        assertNotNull(parameters);
        assertEquals(3, parameters.size());

        var usernameParam = parameters.get(0);
        assertEquals("username", usernameParam.getName());
        assertEquals("User name", usernameParam.getDescription());
        assertEquals(String.class, usernameParam.getClassType());
        assertEquals(true, usernameParam.isRequired());

        var ageParam = parameters.get(1);
        assertEquals("age", ageParam.getName());
        assertEquals("User age", ageParam.getDescription());
        assertEquals(Integer.class, ageParam.getClassType());
        assertEquals(false, ageParam.isRequired());

        var bioParam = parameters.get(2);
        assertEquals("bio", bioParam.getName());
        assertEquals("User bio", bioParam.getDescription());
        assertEquals(String.class, bioParam.getClassType());
        assertEquals(false, bioParam.isRequired());  // default is false
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
