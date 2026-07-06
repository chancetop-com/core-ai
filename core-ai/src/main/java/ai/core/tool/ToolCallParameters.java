package ai.core.tool;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class ToolCallParameters extends ArrayList<ToolCallParameter> {
    @Serial
    private static final long serialVersionUID = -8523451400454678078L;

    public static ParamSpec param(Class<?> type, String name, String description) {
        return ParamSpec.of(type, name, description);
    }

    public static List<ToolCallParameter> of(Class<?>... classes) {
        var parameters = new ArrayList<ToolCallParameter>();
        for (var clazz : classes) {
            if (ToolCallParameterUtil.isCustomObjectType(clazz)) {
                // Custom object: extract all fields
                parameters.addAll(ToolCallParameterUtil.buildObjectFields(clazz));
            } else {
                // Basic type: create a single parameter representing the type
                var parameter = new ToolCallParameter();
                parameter.setName(clazz.getSimpleName().toLowerCase(Locale.ROOT));
                parameter.setClassType(clazz);
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    public static List<ToolCallParameter> of(ParamSpec... specs) {
        var parameters = new ArrayList<ToolCallParameter>();
        for (var spec : specs) {
            var parameter = new ToolCallParameter();
            parameter.setName(spec.getName());
            parameter.setDescription(spec.getDescription());
            parameter.setClassType(spec.getType());
            // If required is not explicitly set, default to false (optional)
            parameter.setRequired(spec.isRequired() != null && spec.isRequired());
            // Set enums if provided
            parameter.setEnums(spec.getEnums());
            // Set defaultValue if provided
            parameter.setDefaultValue(spec.getDefaultValue());
            parameters.add(parameter);
        }
        return parameters;
    }


    public static final class ParamSpec {
        public static ParamSpec of(Class<?> type, String name, String description) {
            return new ParamSpec(type, name, description);
        }

        private final Class<?> type;
        private final String name;
        private final String description;
        private Boolean required;
        private List<String> enums;
        private Supplier<Object> defaultValue;

        private ParamSpec(Class<?> type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
        }

        public ParamSpec required(boolean required) {
            this.required = required;
            return this;
        }

        public ParamSpec required() {
            return required(true);
        }

        public ParamSpec optional() {
            return required(false);
        }

        public ParamSpec enums(List<String> enums) {
            this.enums = enums;
            return this;
        }

        public ParamSpec defaultValue(Supplier<Object> supplier) {
            this.defaultValue = supplier;
            return this;
        }

        Class<?> getType() {
            return this.type;
        }

        String getName() {
            return this.name;
        }

        String getDescription() {
            return this.description;
        }

        Boolean isRequired() {
            return this.required;
        }

        List<String> getEnums() {
            return this.enums;
        }

        Supplier<Object> getDefaultValue() {
            return this.defaultValue;
        }
    }
}
