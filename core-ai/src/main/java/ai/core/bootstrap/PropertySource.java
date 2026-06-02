package ai.core.bootstrap;

import java.util.Optional;
import java.util.Set;

/**
 * @author stephen
 */
@FunctionalInterface
public interface PropertySource {
    Optional<String> property(String key);

    default String requiredProperty(String key) {
        return property(key).orElseThrow(() -> new IllegalStateException("required property not found: " + key));
    }

    default Set<String> propertyNames() {
        return Set.of();
    }
}
