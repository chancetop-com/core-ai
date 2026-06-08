package ai.core.tool.registry;

import java.util.HashSet;
import java.util.Set;

/**
 * Immutable snapshot of the execution context at materialize time.
 * Contains all dimensions that may influence tool availability — model, OS,
 * permissions, feature flags, and network state.
 * <p>
 * New dimensions can be added without affecting existing {@link ToolFilter} implementations.
 *
 * @author Lim Chen
 */
public record ContextSnapshot(
    String modelProvider,
    String modelName,
    OperatingSystem os,
    Set<String> permissions,
    Set<String> featureFlags,
    boolean isOnline
) {
    public enum OperatingSystem {
        MACOS, LINUX, WINDOWS, UNKNOWN
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String modelProvider;
        private String modelName;
        private OperatingSystem os = OperatingSystem.UNKNOWN;
        private final Set<String> permissions = new HashSet<>();
        private final Set<String> featureFlags = new HashSet<>();
        private boolean isOnline = true;

        public Builder modelProvider(String v) {
            modelProvider = v;
            return this;
        }

        public Builder modelName(String v) {
            modelName = v;
            return this;
        }

        public Builder os(OperatingSystem v) {
            os = v;
            return this;
        }

        public Builder addPermission(String v) {
            permissions.add(v);
            return this;
        }

        public Builder addFeatureFlag(String v) {
            featureFlags.add(v);
            return this;
        }

        public Builder isOnline(boolean v) {
            isOnline = v;
            return this;
        }

        public ContextSnapshot build() {
            return new ContextSnapshot(
                modelProvider, modelName, os,
                Set.copyOf(permissions), Set.copyOf(featureFlags), isOnline
            );
        }
    }
}
