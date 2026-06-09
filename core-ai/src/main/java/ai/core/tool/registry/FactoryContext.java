package ai.core.tool.registry;

/**
 * Input to {@link ToolRegistryFactory#create(FactoryContext)}.
 * Declares the environment dimensions that influence provider selection.
 * New dimensions can be added without changing the factory method signature.
 *
 * @param os            operating system identifier (e.g. "macos", "windows")
 * @param modelProvider LLM provider name (e.g. "anthropic", "openai")
 * @author Lim Chen
 */
public record FactoryContext(String os, String modelProvider) {

    public FactoryContext {
        os = os != null ? os.toLowerCase() : null;
        modelProvider = modelProvider != null ? modelProvider.toLowerCase() : null;
    }

    public static FactoryContext of(String os, String modelProvider) {
        return new FactoryContext(os, modelProvider);
    }
}
