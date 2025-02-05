package ai.core.tool.function.converter;

import ai.core.tool.function.converter.parametertype.BooleanConverter;
import ai.core.tool.function.converter.parametertype.DoubleConverter;
import ai.core.tool.function.converter.parametertype.FloatConverter;
import ai.core.tool.function.converter.parametertype.IntegerConverter;
import ai.core.tool.function.converter.parametertype.LongConverter;
import core.framework.util.Strings;

import java.io.Serializable;
import java.util.Map;

/**
 * @author stephen
 */
public class ParameterTypeConverters {
    private static final Map<Class<?>, ParameterTypeConverter<?>> CONVERTERS = Map.of(
            Boolean.class, new BooleanConverter(), boolean.class, new BooleanConverter(),
            Integer.class, new IntegerConverter(), int.class, new IntegerConverter(),
            Long.class, new LongConverter(), long.class, new LongConverter(),
            Double.class, new DoubleConverter(), double.class, new DoubleConverter(),
            Float.class, new FloatConverter(), float.class, new FloatConverter()
    );

    public static Object convert(Object value, Class<?> toType) {
        if (value == null || value.getClass() == String.class && toType != String.class && Strings.isBlank((String) value)) {
            return null;
        }

        if (value.getClass().isAssignableFrom(toType)) return value;


        if (toType == Serializable.class) return value;


        String valueString = value.toString().trim();
        if (valueString.isEmpty()) return null;


        ParameterTypeConverter<?> converter = CONVERTERS.get(toType);
        if (converter != null) return converter.convert(valueString);

        return null;
    }
}
