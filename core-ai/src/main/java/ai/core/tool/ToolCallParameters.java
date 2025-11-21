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
     * Create parameters with type, name, and description triplets
     * @param args Variable arguments in pattern: Class type, String name, String description, ...
     * @return List of ToolCallParameter
     * @throws IllegalArgumentException if arguments length is not multiple of 3 or wrong types
     */
    public static List<ToolCallParameter> of(Object... args) {
        if (args.length % 3 != 0) {
            throw new IllegalArgumentException("Arguments must be in triplets of (Class, String name, String description). Got " + args.length + " arguments.");
        }

        var parameters = new ArrayList<ToolCallParameter>();
        for (int i = 0; i < args.length; i += 3) {
            if (!(args[i] instanceof Class<?> clazz)) {
                throw new IllegalArgumentException("Argument at index " + i + " must be a Class, but got " + args[i].getClass().getName());
            }
            var parameter = getToolCallParameter(args, clazz, i);

            parameters.add(parameter);
        }
        return parameters;
    }

    private static ToolCallParameter getToolCallParameter(Object[] args, Class<?> clazz, int i) {
        if (!(args[i + 1] instanceof String name)) {
            throw new IllegalArgumentException("Argument at index " + (i + 1) + " must be a String (name), but got " + args[i + 1].getClass().getName());
        }
        if (!(args[i + 2] instanceof String description)) {
            throw new IllegalArgumentException("Argument at index " + (i + 2) + " must be a String (description), but got " + args[i + 2].getClass().getName());
        }

        var parameter = new ToolCallParameter();
        parameter.setName(name);
        parameter.setDescription(description);
        parameter.setClassType(clazz);
        return parameter;
    }
}
