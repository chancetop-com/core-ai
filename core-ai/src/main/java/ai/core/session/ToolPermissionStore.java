package ai.core.session;

import ai.core.session.permission.PermissionRule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author stephen
 */
public interface ToolPermissionStore {
    boolean isApproved(String toolName);

    void approve(String toolName);

    Set<String> approvedTools();

    default void addRule(PermissionRule rule) {
    }

    default void removeRule(String toolName, String pathPattern) {
    }

    default List<PermissionRule> getRules() {
        return List.of();
    }

    default Optional<PermissionRule> matchRule(String toolName, Map<String, Object> arguments) {
        return Optional.empty();
    }
}
