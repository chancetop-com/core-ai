package ai.core.tool.registry;

/**
 * Factory for assembling a {@link ToolRegistry} from a {@link FactoryContext}.
 * <p>
 * Always registers {@link BuiltinToolProvider}. Platform and model-specific
 * providers are resolved via {@link #resolveOsProvider} and
 * {@link #resolveModelProvider} — override these in subclasses to
 * add concrete platform tools.
 *
 * @author Lim Chen
 */
public class ToolRegistryFactory {

    public ToolRegistryFactory() {
    }

    public ToolRegistry create(FactoryContext context) {
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

    public ToolProvider resolveOsProvider(String os) {
        return null;
    }

    public ToolProvider resolveModelProvider(String modelProvider) {
        return null;
    }
}
