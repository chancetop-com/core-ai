package ai.core.tool.function.converter.parametertype;

import ai.core.tool.function.converter.ParameterTypeConverter;

import java.util.Locale;

/**
 * @author stephen
 */
public class BooleanConverter implements ParameterTypeConverter<Boolean> {
    @Override
    public Boolean convert(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value)) {
            return Boolean.TRUE;
        } else if ("false".equals(value) || "0".equals(value)) {
            return Boolean.FALSE;
        } else {
            throw new RuntimeException("Can not parse to boolean type of value: " + text);
        }
    }
}
