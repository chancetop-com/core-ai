package ai.core.tool.function.converter;

/**
 * @author stephen
 */
public interface ParameterTypeConverter<T> {
    T convert(String text);
}
