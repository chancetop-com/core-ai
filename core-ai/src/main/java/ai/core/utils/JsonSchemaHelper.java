package ai.core.utils;

import ai.core.api.mcp.JsonSchema;
import ai.core.tool.ToolCallParameterType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public class JsonSchemaHelper {
    public static JsonSchema.PropertyType buildJsonSchemaType(Class<?> c) {
        var t = ToolCallParameterType.getByType(c);
        return buildJsonSchemaType(t);
    }

    public static JsonSchema.PropertyType buildJsonSchemaType(ToolCallParameterType p) {
        var supportType = ToolCallParameterType.getByType(p.getType());
        return switch (supportType) {
            case STRING, ZONEDDATETIME, LOCALDATE, LOCALDATETIME, LOCALTIME -> JsonSchema.PropertyType.STRING;
            case DOUBLE, BIGDECIMAL -> JsonSchema.PropertyType.NUMBER;
            case INTEGER, LONG -> JsonSchema.PropertyType.INTEGER;
            case LIST -> JsonSchema.PropertyType.ARRAY;
            case BOOLEAN -> JsonSchema.PropertyType.BOOLEAN;
            case MAP -> JsonSchema.PropertyType.OBJECT;
        };
    }

    public static String buildJsonSchemaFormat(ToolCallParameterType type) {
        var formatTypes = List.of(
                ToolCallParameterType.ZONEDDATETIME,
                ToolCallParameterType.LOCALDATE,
                ToolCallParameterType.LOCALDATETIME,
                ToolCallParameterType.LOCALTIME);
        if (formatTypes.contains(type)) {
            return type.getType().getSimpleName().substring(type.getType().getSimpleName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        } else {
            return null;
        }
    }

    public static Class<?> mapType(JsonSchema.PropertyType type) {
        return switch (type) {
            case STRING -> String.class;
            case INTEGER -> Integer.class;
            case NUMBER -> Double.class;
            case BOOLEAN -> Boolean.class;
            case ARRAY -> List.class;
            case OBJECT -> Map.class;
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }
}
