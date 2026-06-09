package ai.core.tool.registry;

/**
 * Static factory for assembling a {@link ToolRegistry} from a {@link FactoryContext}.
 * <p>
 * For list-backed registries (used by {@code AgentBuilder}), create a
 * {@link ToolRegistry} directly and call {@link ToolRegistry#registerTools}.
 *
 * @author Lim Chen
 */
public final class ToolRegistryFactory {

    private ToolRegistryFactory() {
    }

    public static ToolRegistry create(FactoryContext context) {
        var registry = new ToolRegistry();
        registry.registerProvider(new BuiltinToolProvider());

        var osProvider = resolveOsProvider(context.os());
        if (osProvider != null) {
            registry.registerProvider(osProvider);
        }

        var modelP = resolveModelProvider(context.modelProvider());
        if (modelP != null) {
            registry.registerProvider(modelP);
        }

        return registry;
    }
    public static ToolRegistry createEmpty() {
        return new ToolRegistry();
    }

    public static ToolProvider resolveOsProvider(String os) {
        return null;
    }

    public static ToolProvider resolveModelProvider(String modelProvider) {
        return null;
    }
}
