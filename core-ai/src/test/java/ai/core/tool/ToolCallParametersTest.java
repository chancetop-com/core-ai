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
