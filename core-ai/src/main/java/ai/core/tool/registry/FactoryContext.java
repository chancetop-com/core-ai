package ai.core.tool.registry;

import ai.core.utils.Platform;

import java.util.Locale;

/**
 * Input to {@link ToolRegistryFactory#create(FactoryContext)}.
 * Declares the environment dimensions that influence provider selection.
 *
 * @author Lim Chen
 */
public record FactoryContext(Platform platform, String modelProvider, boolean todoV2Enabled) {

    public FactoryContext {
        modelProvider = modelProvider != null ? modelProvider.toLowerCase(Locale.ENGLISH) : null;
    }

    public static FactoryContext of(Platform platform, String modelProvider) {
        return new FactoryContext(platform, modelProvider, false);
    }

    public static FactoryContext of(Platform platform, String modelProvider, boolean todoV2Enabled) {
        return new FactoryContext(platform, modelProvider, todoV2Enabled);
    }
}
