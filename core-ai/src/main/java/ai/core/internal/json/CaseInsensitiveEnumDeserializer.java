package ai.core.internal.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/**
 * @author stephen
 */
@SuppressWarnings("rawtypes")
public class CaseInsensitiveEnumDeserializer extends JsonDeserializer<Enum> {

    private final Class enumClass;

    public CaseInsensitiveEnumDeserializer(Class<?> enumClass) {
        this.enumClass = enumClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Enum deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        var value = p.getValueAsString();
        if (value == null || value.isEmpty()) {
            return null;
        }

        try {
            var valuesMethod = enumClass.getMethod("values");
            var constants = (Object[]) valuesMethod.invoke(null);

            for (var constant : constants) {
                var e = (Enum) constant;
                if (e.name().equalsIgnoreCase(value)) {
                    return e;
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to deserialize enum " + enumClass.getName(), e);
        }

        throw new InvalidFormatException(p, "Cannot deserialize value of type " + enumClass.getName() + " from String \"" + value + "\"", value, enumClass);
    }
}
