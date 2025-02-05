package ai.core.tool.function.converter.parametertype;

import ai.core.tool.function.converter.ParameterTypeConverter;

/**
 * @author stephen
 */
public class LongConverter implements ParameterTypeConverter<Long> {
    @Override
    public Long convert(String text) {
        return Long.valueOf(text);
    }
}
