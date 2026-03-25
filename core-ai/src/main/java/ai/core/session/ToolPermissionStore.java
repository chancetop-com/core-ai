package ai.core.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ToolPermissionStore {
    void allow(String pattern);

    void deny(String pattern);

    Optional<Boolean> checkPermission(String toolName, Map<String, Object> arguments);

    default void allowDir(String toolName, String dirPath) {
        String normalized = dirPath.replace('\\', '/');
        if (!normalized.endsWith("/")) normalized += "/";
        allow(toolName + "(" + normalized + "**)");
    }

    default void allowDirs(List<String> toolNames, String dirPath) {
        for (String toolName : toolNames) {
            allowDir(toolName, dirPath);
        }
    }
}
