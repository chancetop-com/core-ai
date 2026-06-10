package ai.core.tool.registry;

import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.utils.Platform;

import java.util.List;

/**
 * Static factory for assembling a {@link ToolRegistry} from a {@link FactoryContext}.
 * <p>
 * Tool groups are registered as separate providers so that platform-specific or
 * model-specific providers can override or supplement individual groups.
 *
 * @author Lim Chen
 */
public final class ToolRegistryFactory {

    private ToolRegistryFactory() {
    }

    public static ToolRegistry create(FactoryContext context) {
        var registry = new ToolRegistry();

        registry.registerProvider(new BuiltinToolProvider(ToolProvider.BUILTIN_PLANNING, planningTools(context)));
        registry.registerProvider(new BuiltinToolProvider(ToolProvider.BUILTIN_FILES, fileTools(context)));

        registry.registerProvider(new BuiltinToolProvider(ToolProvider.BUILTIN_MULTIMODAL, BuiltinTools.MULTIMODAL));
        registry.registerProvider(new BuiltinToolProvider(ToolProvider.BUILTIN_WEB, BuiltinTools.WEB));
        registry.registerProvider(new BuiltinToolProvider(ToolProvider.BUILTIN_BASH, executableTools(context)));
        return registry;
    }

    public static ToolRegistry createEmpty() {
        return new ToolRegistry();
    }


    private static List<ToolCall> planningTools(FactoryContext context) {
        if (context.todoV2Enabled()) {
            return BuiltinTools.PLANNING_V2;
        }
        return BuiltinTools.PLANNING;
    }

    private static List<ToolCall> executableTools(FactoryContext context) {
        if (context.platform().isWindows()) {
            return BuiltinTools.POWERSHELL_EXECUTION;
        } else {
            return BuiltinTools.BASH_EXECUTION;
        }
    }

    private static List<ToolCall> fileTools(FactoryContext context) {
        if (context.platform().isWindows()) {
            return BuiltinTools.FILE_OPERATIONS;
        } else {
            return BuiltinTools.FILE_RW;
        }
    }
}
