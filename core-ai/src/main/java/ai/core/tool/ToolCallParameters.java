package ai.core.tool;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public class ToolCallParameters extends ArrayList<ToolCallParameter> {
    @Serial
    private static final long serialVersionUID = -8523451400454678078L;

    /**
     * Convenient static method to create ParamSpec (for static import)
     * @param type Parameter type class
     * @param name Parameter name
     * @param description Parameter description
     * @return ParamSpec instance
     */
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

    /**
     * Create parameters from parameter specifications
     * @param specs Variable arguments of ParamSpec
     * @return List of ToolCallParameter
     */
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
            parameters.add(parameter);
        }
        return parameters;
    }


    /**
     * Parameter specification for building tool parameters
     */
    public static final class ParamSpec {
        /**
         * Create a parameter specification
         * @param type Parameter type class
         * @param name Parameter name
         * @param description Parameter description
         * @return ParamSpec instance
         */
        public static ParamSpec of(Class<?> type, String name, String description) {
            return new ParamSpec(type, name, description);
        }

        private final Class<?> type;
        private final String name;
        private final String description;
        private Boolean required;
        private List<String> enums;

        private ParamSpec(Class<?> type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
        }

        /**
         * Set whether this parameter is required
         * @param required true if required, false if optional
         * @return this ParamSpec for chaining
         */
        public ParamSpec required(boolean required) {
            this.required = required;
            return this;
        }

        /**
         * Mark this parameter as required
         * @return this ParamSpec for chaining
         */
        public ParamSpec required() {
            return required(true);
        }

        /**
         * Mark this parameter as optional
         * @return this ParamSpec for chaining
         */
        public ParamSpec optional() {
            return required(false);
        }

        /**
         * Set allowed enum values for this parameter
         * @param enums List of allowed string values
         * @return this ParamSpec for chaining
         */
        public ParamSpec enums(List<String> enums) {
            this.enums = enums;
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
    }
}
