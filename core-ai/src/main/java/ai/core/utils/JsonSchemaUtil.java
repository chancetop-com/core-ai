package ai.core.utils;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import core.framework.api.json.Property;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class JsonSchemaUtil {
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

    public static JsonSchema toJsonSchema(Class<?> modelClass) {
        var parameters = new ArrayList<ToolCallParameter>();

        // Get all fields from the class
        var fields = modelClass.getDeclaredFields();

        for (var field : fields) {
            // Skip synthetic fields
            if (field.isSynthetic()) {
                continue;
            }

            var parameter = ToolCallParameter.builder();

            // Get field name from @Property annotation or use field name
            var propertyAnnotation = field.getAnnotation(Property.class);
            var fieldName = propertyAnnotation != null ? propertyAnnotation.name() : field.getName();
            parameter.name(fieldName);

            // Set class type
            var fieldType = field.getType();
            parameter.classType(fieldType);

            // Handle List type with generic parameter
            if (List.class.isAssignableFrom(fieldType) && field.getGenericType() instanceof ParameterizedType paramType) {
                var itemType = (Class<?>) paramType.getActualTypeArguments()[0];
                parameter.itemType(itemType);
            }

            // Handle Map type
            if (Map.class.isAssignableFrom(fieldType) && field.getGenericType() instanceof ParameterizedType paramType) {
                var valueType = (Class<?>) paramType.getActualTypeArguments()[1];
                parameter.itemType(valueType);
            }

            // All fields are considered required by default
            // You can add logic here to check for annotations like @Nullable
            parameter.required(true);

            parameters.add(parameter.build());
        }

        return toJsonSchema(parameters);
    }

    public static JsonSchema toJsonSchema(List<ToolCallParameter> parameters) {
        return buildSchema(parameters, null);
    }

    private static JsonSchema buildSchema(List<ToolCallParameter> parameters, ToolCallParameter parent) {
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;
        if (parent != null) {
            schema.enums = parent.getItemEnums();
        }
        schema.required = parameters.stream()
                .filter(v -> v.isRequired() != null && v.isRequired() && v.getName() != null)
                .map(ToolCallParameter::getName)
                .toList();
        schema.properties = parameters.stream()
                .filter(v -> v.getName() != null)
                .collect(Collectors.toMap(ToolCallParameter::getName, JsonSchemaUtil::toSchemaProperty, (v1, v2) -> v1));
        return schema;
    }

    private static JsonSchema toSchemaProperty(ToolCallParameter p) {
        var property = new JsonSchema();
        property.description = p.getDescription();
        property.type = buildJsonSchemaType(p.getClassType());
        property.enums = p.getEnums();
        property.format = p.getFormat();
        if (property.type == JsonSchema.PropertyType.ARRAY) {
            if (p.getItems() != null && !p.getItems().isEmpty()) {
                property.items = buildSchema(p.getItems(), p);
            } else {
                property.items = new JsonSchema();
                property.items.type = toPropertyType(p.getItemType());
                property.enums = p.getItemEnums();
            }
        }
        return property;
    }

    private static JsonSchema.PropertyType toPropertyType(Class<?> c) {
        if (c == null) {
            return JsonSchema.PropertyType.STRING;
        }
        var n = c.getSimpleName().substring(c.getSimpleName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        if ("object".equalsIgnoreCase(n)) {
            return JsonSchema.PropertyType.OBJECT;
        }
        return buildJsonSchemaType(c);
    }
}
