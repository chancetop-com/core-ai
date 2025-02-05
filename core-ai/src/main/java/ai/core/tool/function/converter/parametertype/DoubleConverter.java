package ai.core.tool.function.converter.parametertype;

import ai.core.tool.function.converter.ParameterTypeConverter;

/**
 * @author stephen
 */
public class DoubleConverter implements ParameterTypeConverter<Double> {
    @Override
    public Double convert(String text) {
        return Double.valueOf(text);
    }
}
