package ai.core.session;

import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ToolPermissionStore {
    void allow(String pattern);

    void deny(String pattern);

    Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments);
}
