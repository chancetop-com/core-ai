package ai.core.session;

import java.util.Set;

/**
 * @author stephen
 */
public interface ToolPermissionStore {
    boolean isApproved(String toolName);

    void approve(String toolName);

    Set<String> approvedTools();
}
