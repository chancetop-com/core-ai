package ai.core.tool.function.converter.parametertype;

import ai.core.tool.function.converter.ParameterTypeConverter;

/**
 * @author stephen
 */
public class IntegerConverter implements ParameterTypeConverter<Integer> {
    @Override
    public Integer convert(String text) {
        return Integer.valueOf(text);
    }
}
