package ai.core.internal.json;

import ai.core.api.tool.function.CoreAiParameter;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import core.framework.internal.json.JSONAnnotationIntrospector;

import java.io.Serial;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class CoreAiAnnotationIntrospector extends JSONAnnotationIntrospector {
    @Serial
    private static final long serialVersionUID = 7351589562498891688L;

    @Override
    public PropertyName findNameForSerialization(Annotated annotated) {
        var name = coreAiPropertyName(annotated);
        if (name != null) return name;
        return super.findNameForSerialization(annotated);
    }

    @Override
    public PropertyName findNameForDeserialization(Annotated annotated) {
        var name = coreAiPropertyName(annotated);
        if (name != null) return name;
        return super.findNameForDeserialization(annotated);
    }

    @Override
    public String[] findEnumValues(MapperConfig<?> config, AnnotatedClass annotatedClass, Enum<?>[] enumValues, String[] names) {
        var result = super.findEnumValues(config, annotatedClass, enumValues, names);

        Map<String, String> mappings = null;
        for (AnnotatedField field : annotatedClass.fields()) {
            if (!field.getAnnotated().isEnumConstant()) continue;

            var parameter = field.getAnnotation(CoreAiParameter.class);
            if (parameter == null) continue;

            var value = parameter.name();
            if (value.isEmpty()) continue;

            if (mappings == null) mappings = new HashMap<>();
            mappings.put(field.getName(), value);
        }

        if (mappings != null) {
            var length = enumValues.length;
            for (var i = 0; i < length; i++) {
                var enumName = enumValues[i].name();
                var value = mappings.get(enumName);
                if (value != null) result[i] = value;
            }
        }
        return result;
    }

    private PropertyName coreAiPropertyName(Annotated annotated) {
        var parameter = annotated.getAnnotation(CoreAiParameter.class);
        if (parameter != null && !parameter.name().isEmpty()) {
            return new PropertyName(parameter.name(), null);
        }
        return null;
    }
}
