package ai.core.tool.function.converter.parametertype;

import ai.core.tool.function.converter.ParameterTypeConverter;

/**
 * @author stephen
 */
public class FloatConverter implements ParameterTypeConverter<Float> {
    @Override
    public Float convert(String text) {
        return Float.parseFloat(text);
    }
}
