package ai.core.tool;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class ToolCallParameterUtil {

    public static List<ToolCallParameter> buildObjectFields(Class<?> clazz) {
        var fields = new ArrayList<ToolCallParameter>();
        for (var field : clazz.getDeclaredFields()) {
            if (shouldSkipField(field)) continue;

            var fieldParam = buildFieldParameter(field);
            fields.add(fieldParam);
        }
        return fields;
    }

    public static boolean shouldSkipField(Field field) {
        return Modifier.isStatic(field.getModifiers()) || field.isSynthetic();
    }

    public static ToolCallParameter buildFieldParameter(Field field) {
        var fieldParam = new ToolCallParameter();
        fieldParam.setName(field.getName());

        if (field.getType().isEnum()) {
            setEnumFieldParameter(fieldParam, field.getType());
        } else {
            setRegularFieldParameter(fieldParam, field);
        }

        return fieldParam;
    }

    public static void setEnumFieldParameter(ToolCallParameter fieldParam, Class<?> enumType) {
        fieldParam.setClassType(String.class);
        var enumConstants = enumType.getEnumConstants();
        var enumValues = new ArrayList<String>();
        for (Object enumConstant : enumConstants) {
            enumValues.add(enumConstant.toString());
        }
        fieldParam.setEnums(enumValues);
    }

    public static void setRegularFieldParameter(ToolCallParameter fieldParam, Field field) {
        fieldParam.setClassType(field.getType());
        extractGenericItemType(field.getGenericType(), fieldParam);
    }

    public static void extractGenericItemType(java.lang.reflect.Type parameterizedType, ToolCallParameter parameter) {
        if (!(parameterizedType instanceof ParameterizedType)) return;

        var actualTypeArguments = ((ParameterizedType) parameterizedType).getActualTypeArguments();
        if (actualTypeArguments.length == 0 || !(actualTypeArguments[0] instanceof Class<?> itemClass)) return;

        parameter.setItemType(itemClass);
        if (isCustomObjectType(itemClass)) {
            parameter.setItems(buildObjectFields(itemClass));
        }
    }

    public static boolean isCustomObjectType(Class<?> clazz) {
        return !clazz.isPrimitive()
                && !clazz.getName().startsWith("java.lang")
                && !clazz.getName().startsWith("java.util")
                && !clazz.getName().startsWith("java.time")
                && !clazz.isEnum();
    }
}
