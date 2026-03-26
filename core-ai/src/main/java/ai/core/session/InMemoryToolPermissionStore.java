package ai.core.session;

import ai.core.session.permission.PermissionRule;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class InMemoryToolPermissionStore implements ToolPermissionStore {
    private final Set<String> allowedPatterns = ConcurrentHashMap.newKeySet();

    @Override
    public void allow(String pattern) {
        allowedPatterns.add(pattern);
    }

    @Override
    public void deny(String pattern) {
        allowedPatterns.remove(pattern);
    }

    @Override
    public Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments) {
        // Auto-allow read-only operations
        if (PermissionRule.isReadOnly(toolName, arguments)) {
            return Optional.of(true);
        }

        if (allowedPatterns.stream().anyMatch(p -> PermissionRule.matches(p, toolName, arguments))) {
            return Optional.of(true);
        }
        return Optional.empty();
    }
}
