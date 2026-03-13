package ai.core.session;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class InMemoryToolPermissionStore implements ToolPermissionStore {
    private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();

    @Override
    public void allow(String toolName, String pathPattern) {
        allowedTools.add(toolName);
    }

    @Override
    public void deny(String toolName, String pathPattern) {
        allowedTools.remove(toolName);
    }

    @Override
    public Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments) {
        if (allowedTools.contains(toolName)) return Optional.of(true);
        return Optional.empty();
    }
}
