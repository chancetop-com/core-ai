package ai.core.cli.hub;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HubArgumentBuilderTest {
    private final HubArgumentBuilder builder = new HubArgumentBuilder();

    @Test
    void argsJsonIsPassedThroughNormalized() {
        var result = builder.build("{\"a\": 1, \"b\":\"x\"}", null, null, null);
        assertEquals(Map.of("a", 1, "b", "x"), JsonUtil.toMap(result));
    }

    @Test
    void argsFileWinsOverArgsJson() {
        var result = builder.build("{\"a\":1}", "{\"a\":2, \"c\":3}", null, null);
        assertEquals(Map.of("a", 2, "c", 3), JsonUtil.toMap(result));
    }

    @Test
    void argAssignmentsCoerceBySchemaType() {
        var schema = "{\"properties\":{\"count\":{\"type\":\"integer\"},\"verbose\":{\"type\":\"boolean\"},"
                + "\"ratio\":{\"type\":\"number\"},\"tags\":{\"type\":\"array\"},\"meta\":{\"type\":\"object\"},"
                + "\"summary\":{\"type\":\"string\"}}}";
        var result = builder.build(null, null,
                List.of("count=12", "verbose=true", "ratio=1.5", "tags=[\"a\",\"b\"]", "meta={\"k\":1}", "summary=hello"),
                schema);
        var map = JsonUtil.toMap(result);
        assertEquals(12, ((Number) map.get("count")).intValue());
        assertEquals(Boolean.TRUE, map.get("verbose"));
        assertEquals(1.5D, map.get("ratio"));
        assertEquals(List.of("a", "b"), map.get("tags"));
        assertEquals(Map.of("k", 1), map.get("meta"));
        assertEquals("hello", map.get("summary"));
    }

    @Test
    void argValueFailingCoercionStaysString() {
        var schema = "{\"properties\":{\"count\":{\"type\":\"integer\"}}}";
        var result = builder.build(null, null, List.of("count=abc"), schema);
        assertEquals("abc", JsonUtil.toMap(result).get("count"));
    }

    @Test
    void argWithoutSchemaTypeStaysString() {
        var result = builder.build(null, null, List.of("project=CORE", "draft=true"), null);
        var map = JsonUtil.toMap(result);
        assertEquals("CORE", map.get("project"));
        assertEquals("true", map.get("draft"));
    }

    @Test
    void assignmentWithoutEqualsIsUsageError() {
        var error = assertThrows(HubCliError.class, () -> builder.build(null, null, List.of("project"), null));
        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    @Test
    void malformedArgsJsonIsUsageError() {
        var error = assertThrows(HubCliError.class, () -> builder.build("{broken", null, null, null));
        assertEquals(HubExitCodes.USAGE, error.exitCode);
    }

    @Test
    void emptyArgumentsProduceEmptyObject() {
        assertEquals("{}", builder.build(null, null, null, null));
    }
}
