package ai.core.session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class InMemoryToolPermissionStore implements ToolPermissionStore {
    private final Set<String> approvedTools = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isApproved(String toolName) {
        return approvedTools.contains(toolName);
    }

    @Override
    public void approve(String toolName) {
        approvedTools.add(toolName);
    }

    @Override
    public Set<String> approvedTools() {
        return Set.copyOf(approvedTools);
    }
}
