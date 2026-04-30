package ai.core.internal.json;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.api.json.Property;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaseInsensitiveEnumDeserializerTest {

    @Test
    void testEnumCaseInsensitive() {
        // Test UPPERCASE
        String json = "{\"name\":\"test\",\"status\":\"IN_PROGRESS\"}";
        EntityWithEnum entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.IN_PROGRESS, entity.status);

        // Test lowercase
        json = "{\"name\":\"test\",\"status\":\"in_progress\"}";
        entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.IN_PROGRESS, entity.status);

        // Test mixed case
        json = "{\"name\":\"test\",\"status\":\"In_Progress\"}";
        entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.IN_PROGRESS, entity.status);

        // Test pending
        json = "{\"name\":\"test\",\"status\":\"PENDING\"}";
        entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.PENDING, entity.status);

        json = "{\"name\":\"test\",\"status\":\"pending\"}";
        entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.PENDING, entity.status);

        // Test completed
        json = "{\"name\":\"test\",\"status\":\"COMPLETED\"}";
        entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.COMPLETED, entity.status);

        json = "{\"name\":\"test\",\"status\":\"completed\"}";
        entity = JsonUtil.fromJson(EntityWithEnum.class, json);
        assertEquals(TestStatus.COMPLETED, entity.status);
    }

    @Test
    void testEnumWithPropertyAnnotation() {
        // Test lowercase (as defined in @Property)
        String json = "{\"name\":\"test\",\"status\":\"in_progress\"}";
        EntityWithEnumProperty entity = JsonUtil.fromJson(EntityWithEnumProperty.class, json);
        assertEquals(TestStatusWithProperty.IN_PROGRESS, entity.status);

        // Test UPPERCASE (should also work due to case-insensitive deserializer)
        json = "{\"name\":\"test\",\"status\":\"IN_PROGRESS\"}";
        entity = JsonUtil.fromJson(EntityWithEnumProperty.class, json);
        assertEquals(TestStatusWithProperty.IN_PROGRESS, entity.status);

        // Test mixed case
        json = "{\"name\":\"test\",\"status\":\"In_Progress\"}";
        entity = JsonUtil.fromJson(EntityWithEnumProperty.class, json);
        assertEquals(TestStatusWithProperty.IN_PROGRESS, entity.status);
    }

    @Test
    void testEnumListCaseInsensitive() {
        String json = "{\"name\":\"test\",\"statuses\":[\"PENDING\",\"in_progress\",\"Completed\"]}";
        Type type = new TypeReference<Map<String, Object>>() { }.getType();
        Map<String, Object> map = JsonUtil.fromJson(type, json);

        EntityWithEnumList entity = JsonUtil.fromJson(EntityWithEnumList.class, JsonUtil.toJson(map));
        assertEquals(3, entity.statuses.size());
        assertEquals(TestStatus.PENDING, entity.statuses.get(0));
        assertEquals(TestStatus.IN_PROGRESS, entity.statuses.get(1));
        assertEquals(TestStatus.COMPLETED, entity.statuses.get(2));
    }

    @Test
    void testEnumSerialization() {
        EntityWithEnum entity = new EntityWithEnum();
        entity.name = "test";
        entity.status = TestStatus.IN_PROGRESS;

        String json = JsonUtil.toJson(entity);
        assertEquals("{\"name\":\"test\",\"status\":\"IN_PROGRESS\"}", json);
    }

    @Test
    void testEnumWithPropertySerialization() {
        EntityWithEnumProperty entity = new EntityWithEnumProperty();
        entity.name = "test";
        entity.status = TestStatusWithProperty.IN_PROGRESS;

        String json = JsonUtil.toJson(entity);
        // @Property annotation should serialize as lowercase
        assertEquals("{\"name\":\"test\",\"status\":\"in_progress\"}", json);
    }

    @Test
    void testWriteTodosToolStatus() {
        // Test the actual WriteTodosTool.Status enum used in production
        String json = "[{\"content\":\"Task 1\",\"status\":\"IN_PROGRESS\"}]";
        Type type = new TypeReference<List<ai.core.tool.tools.WriteTodosTool.Todo>>() { }.getType();
        List<ai.core.tool.tools.WriteTodosTool.Todo> todos = JsonUtil.fromJson(type, json);
        assertEquals(ai.core.tool.tools.WriteTodosTool.Status.IN_PROGRESS, todos.get(0).status);

        // Test lowercase
        json = "[{\"content\":\"Task 2\",\"status\":\"pending\"}]";
        todos = JsonUtil.fromJson(type, json);
        assertEquals(ai.core.tool.tools.WriteTodosTool.Status.PENDING, todos.get(0).status);

        // Test mixed case
        json = "[{\"content\":\"Task 3\",\"status\":\"Completed\"}]";
        todos = JsonUtil.fromJson(type, json);
        assertEquals(ai.core.tool.tools.WriteTodosTool.Status.COMPLETED, todos.get(0).status);
    }

    enum TestStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    enum TestStatusWithProperty {
        @Property(name = "pending")
        PENDING,
        @Property(name = "in_progress")
        IN_PROGRESS,
        @Property(name = "completed")
        COMPLETED
    }

    static class EntityWithEnum {
        public String name;
        public TestStatus status;
    }

    static class EntityWithEnumProperty {
        public String name;
        public TestStatusWithProperty status;
    }

    static class EntityWithEnumList {
        public String name;
        public List<TestStatus> statuses;
    }
}
