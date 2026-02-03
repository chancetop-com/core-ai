package ai.core.utils;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import core.framework.api.json.Property;

import java.util.Arrays;

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
        if (t == null) {
            // Custom/nested object type
            return JsonSchema.PropertyType.OBJECT;
        }
        return buildJsonSchemaType(t);
    }

    public static JsonSchema.PropertyType buildJsonSchemaType(ToolCallParameterType p) {
        if (p == null) {
            return JsonSchema.PropertyType.OBJECT;
        }
        var supportType = ToolCallParameterType.getByType(p.getType());
        if (supportType == null) {
            return JsonSchema.PropertyType.OBJECT;
        }
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

            // Prioritize @CoreAiParameter annotation, fallback to @Property annotation
            var coreAiAnnotation = field.getAnnotation(CoreAiParameter.class);
            var propertyAnnotation = field.getAnnotation(Property.class);

            // Get field name
            String fieldName;
            if (coreAiAnnotation != null) {
                fieldName = coreAiAnnotation.name();
            } else if (propertyAnnotation != null) {
                fieldName = propertyAnnotation.name();
            } else {
                fieldName = field.getName();
            }
            parameter.name(fieldName);

            // Set description from @CoreAiParameter if present
            if (coreAiAnnotation != null && !coreAiAnnotation.description().isEmpty()) {
                parameter.description(coreAiAnnotation.description());
            }

            // Set enums from @CoreAiParameter if present
            if (coreAiAnnotation != null && coreAiAnnotation.enums().length > 0) {
                parameter.enums(Arrays.asList(coreAiAnnotation.enums()));
            }

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

            // Set required: prioritize @CoreAiParameter, default to true
            if (coreAiAnnotation != null) {
                parameter.required(coreAiAnnotation.required());
            } else {
                parameter.required(true);
            }

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
            property.items = buildArrayItemSchema(p);
        } else if (property.type == JsonSchema.PropertyType.OBJECT && isCustomObjectType(p.getClassType())) {
            var nestedSchema = toJsonSchema(p.getClassType());
            property.properties = nestedSchema.properties;
            property.required = nestedSchema.required;
        }
        if (p.getClassType() != null && p.getClassType().isEnum()) {
            property.type = JsonSchema.PropertyType.STRING;
            property.enums = getEnumValues(p.getClassType());
        }
        return property;
    }

    private static JsonSchema buildArrayItemSchema(ToolCallParameter p) {
        if (p.getItems() != null && !p.getItems().isEmpty()) {
            return buildSchema(p.getItems(), p);
        }
        var itemSchema = new JsonSchema();
        itemSchema.type = toPropertyType(p.getItemType());
        itemSchema.enums = p.getItemEnums();
        if (itemSchema.type == JsonSchema.PropertyType.OBJECT && p.getItemType() != null && isCustomObjectType(p.getItemType())) {
            var nestedSchema = toJsonSchema(p.getItemType());
            itemSchema.properties = nestedSchema.properties;
            itemSchema.required = nestedSchema.required;
        }
        return itemSchema;
    }

    private static boolean isCustomObjectType(Class<?> c) {
        if (c == null) return false;
        var t = ToolCallParameterType.getByType(c);
        return t == null && !c.isEnum();
    }

    private static List<String> getEnumValues(Class<?> enumClass) {
        var constants = enumClass.getEnumConstants();
        if (constants == null) return null;
        var values = new ArrayList<String>();
        for (var constant : constants) {
            try {
                var field = enumClass.getField(((Enum<?>) constant).name());
                var propertyAnnotation = field.getAnnotation(Property.class);
                if (propertyAnnotation != null) {
                    values.add(propertyAnnotation.name());
                } else {
                    values.add(((Enum<?>) constant).name());
                }
            } catch (NoSuchFieldException e) {
                values.add(((Enum<?>) constant).name());
            }
        }
        return values;
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
