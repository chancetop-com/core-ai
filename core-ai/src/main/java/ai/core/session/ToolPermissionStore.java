package ai.core.session;

import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ToolPermissionStore {
    void allow(String toolName, String pathPattern);

    void deny(String toolName, String pathPattern);

    Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments);
}
