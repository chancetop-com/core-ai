package ai.core.internal.json;

import ai.core.api.tool.function.CoreAiParameter;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class CoreAiAnnotationIntrospectorTest {
    @Test
    void coreAiParameterUsedAsJsonKey() {
        var obj = new WithCoreAiParameter();
        obj.value = "test";
        String json = JsonUtil.toJson(obj);
        assertEquals("{\"custom_name\":\"test\"}", json);

        var deserialized = JsonUtil.fromJson(WithCoreAiParameter.class, json);
        assertEquals("test", deserialized.value);
    }

    @Test
    void propertyUsedWhenNoCoreAiParameter() {
        var obj = new WithPropertyOnly();
        obj.value = "test";
        String json = JsonUtil.toJson(obj);
        assertEquals("{\"prop_name\":\"test\"}", json);

        var deserialized = JsonUtil.fromJson(WithPropertyOnly.class, json);
        assertEquals("test", deserialized.value);
    }

    @Test
    void coreAiParameterTakesPriorityOverProperty() {
        var obj = new WithBothAnnotations();
        obj.value = "test";
        String json = JsonUtil.toJson(obj);
        assertEquals("{\"core_ai_name\":\"test\"}", json);

        var deserialized = JsonUtil.fromJson(WithBothAnnotations.class, json);
        assertEquals("test", deserialized.value);
    }

    @Test
    void nullFieldNotSerialized() {
        var obj = new WithCoreAiParameter();
        obj.value = null;
        String json = JsonUtil.toJson(obj);
        assertEquals("{}", json);
    }

    @Test
    void enumWithCoreAiParameter() {
        var obj = new WithEnumField();
        obj.status = TestStatus.ACTIVE;
        String json = JsonUtil.toJson(obj);
        assertEquals("{\"status\":\"active\"}", json);

        var deserialized = JsonUtil.fromJson(WithEnumField.class, json);
        assertEquals(TestStatus.ACTIVE, deserialized.status);
    }

    public enum TestStatus {
        @CoreAiParameter(name = "active", description = "active status")
        ACTIVE,
        @CoreAiParameter(name = "inactive", description = "inactive status")
        INACTIVE
    }

    public static class WithEnumField {
        @Property(name = "status")
        public TestStatus status;
    }

    public static class WithCoreAiParameter {
        @CoreAiParameter(name = "custom_name", description = "a custom field")
        public String value;
    }

    public static class WithPropertyOnly {
        @Property(name = "prop_name")
        public String value;
    }

    public static class WithBothAnnotations {
        @CoreAiParameter(name = "core_ai_name", description = "core ai field")
        @Property(name = "prop_name")
        public String value;
    }
}
