package ai.core.bootstrap;

import java.util.Optional;

/**
 * @author stephen
 */
@FunctionalInterface
public interface PropertySource {
    Optional<String> property(String key);

    default String requiredProperty(String key) {
        return property(key).orElseThrow(() -> new IllegalStateException("required property not found: " + key));
    }
}
