package ai.core.tool.registry;

import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistryFactory.class);

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
        var individualTools = new LinkedHashMap<String, ToolCall>();

        for (var name : names) {
            var provider = source.getProvider(name);
            if (provider != null) {
                derived.registerProvider(provider);
                continue;
            }
            var tool = dispatchMap.get(name);
            if (tool != null) {
                individualTools.putIfAbsent(name, tool);
                continue;
            }
            tool = searchAcrossProviders(source, name);
            if (tool != null) {
                individualTools.putIfAbsent(name, tool);
                continue;
            }
            var groupTools = BuiltinTools.GROUPED_SETS.get(name);
            if (groupTools != null) {
                for (var tc : groupTools) {
                    var expandedTool = dispatchMap.get(tc.getName());
                    if (expandedTool != null) {
                        individualTools.putIfAbsent(tc.getName(), expandedTool);
                        continue;
                    }
                    expandedTool = searchAcrossProviders(source, tc.getName());
                    if (expandedTool != null) {
                        individualTools.putIfAbsent(tc.getName(), expandedTool);
                    }
                }
            }
        }

        if (!individualTools.isEmpty()) {
            derived.registerProvider(ListToolProvider.of(new ArrayList<>(individualTools.values())));
        }

        if (derived.getToolCalls().isEmpty() && !names.isEmpty()) {
            LOGGER.warn("derive matched no tools, names={}", names);
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

    private ToolRegistryFactory() {
    }
}
