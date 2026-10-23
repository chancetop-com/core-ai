package ai.core.tool.registry;

import ai.core.utils.Platform;

import java.util.Locale;
import java.util.Set;

/**
 * Input to {@link ToolRegistryFactory#create(FactoryContext)}.
 * Declares the environment dimensions that influence provider selection.
 *
 * @author Lim Chen
 */
public record FactoryContext(Platform platform, String modelProvider, boolean todoV2Enabled, Set<String> disabledTools) {

    public FactoryContext {
        modelProvider = modelProvider != null ? modelProvider.toLowerCase(Locale.ENGLISH) : null;
        disabledTools = disabledTools != null ? Set.copyOf(disabledTools) : Set.of();
    }

    public FactoryContext(Platform platform, String modelProvider, boolean todoV2Enabled) {
        this(platform, modelProvider, todoV2Enabled, Set.of());
    }

    public static FactoryContext of(Platform platform, String modelProvider) {
        return new FactoryContext(platform, modelProvider, false);
    }

    public static FactoryContext of(Platform platform, String modelProvider, boolean todoV2Enabled) {
        return new FactoryContext(platform, modelProvider, todoV2Enabled);
    }

    public static FactoryContext of(Platform platform, String modelProvider, boolean todoV2Enabled,
                                    Set<String> disabledTools) {
        return new FactoryContext(platform, modelProvider, todoV2Enabled, disabledTools);
    }
}
