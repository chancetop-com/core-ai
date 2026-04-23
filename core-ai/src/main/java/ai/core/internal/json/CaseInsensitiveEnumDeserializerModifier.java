package ai.core.internal.json;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;

import java.io.Serial;

/**
 * @author stephen
 */
public class CaseInsensitiveEnumDeserializerModifier extends BeanDeserializerModifier {
    @Serial
    private static final long serialVersionUID = -3862260977984376262L;

    @Override
    public JsonDeserializer<?> modifyEnumDeserializer(DeserializationConfig config, JavaType type, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
        if (type.isEnumType()) {
            return new CaseInsensitiveEnumDeserializer(type.getRawClass());
        }
        return deserializer;
    }
}
