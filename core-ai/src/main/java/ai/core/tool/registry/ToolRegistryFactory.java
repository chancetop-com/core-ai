package ai.core.tool.registry;

import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public static ToolRegistry derive(ToolRegistry source, Set<String> names) {
        var derived = new ToolRegistry();
        var mat = source.materialize();
        var dispatchMap = mat.getDispatchMap();
        var individualTools = new ArrayList<ToolCall>();
        var foundProviderIds = new HashSet<String>();

        for (var name : names) {
            var provider = source.getProvider(name);
            if (provider != null) {
                derived.registerProvider(provider);
                foundProviderIds.add(name);
            } else {
                var tool = dispatchMap.get(name);
                if (tool != null) {
                    individualTools.add(tool);
                } else {
                    tool = searchAcrossProviders(source, name);
                    if (tool != null) {
                        individualTools.add(tool);
                    }
                }
            }
        }

        if (!individualTools.isEmpty()) {
            derived.registerProvider(ListToolProvider.of(individualTools));
        }

        // expand GROUPED_SETS keys to individual tool names before falling back
        if (derived.getToolCalls().isEmpty()) {
            var expandedNames = new ArrayList<String>();
            for (var name : names) {
                var tools = BuiltinTools.GROUPED_SETS.get(name);
                if (tools != null) {
                    for (var tc : tools) expandedNames.add(tc.getName());
                }
            }
            if (!expandedNames.isEmpty()) {
                var expandedTools = new ArrayList<ToolCall>();
                for (var toolName : expandedNames) {
                    var tool = dispatchMap.get(toolName);
                    if (tool != null) {
                        expandedTools.add(tool);
                    } else {
                        tool = searchAcrossProviders(source, toolName);
                        if (tool != null) expandedTools.add(tool);
                    }
                }
                if (!expandedTools.isEmpty()) {
                    derived.registerProvider(ListToolProvider.of(expandedTools));
                }
            }
        }

        if (derived.getToolCalls().isEmpty() && !dispatchMap.isEmpty()) {
            for (var entry : source.providers().entrySet()) {
                if (!foundProviderIds.contains(entry.getKey())) {
                    derived.registerProvider(entry.getValue());
                }
            }
        }

        return derived;
    }

    private static ToolCall searchAcrossProviders(ToolRegistry source, String name) {
        for (var entry : source.providers().entrySet()) {
            var tools = entry.getValue().provide();
            var tool = tools.get(name);
            if (tool != null) {
                return tool;
            }
        }
        return null;
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
