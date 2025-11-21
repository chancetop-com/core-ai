package ai.core.tool;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

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
                parameter.setClassType(clazz);
                parameters.add(parameter);
            }
        }
        return parameters;
    }
}
