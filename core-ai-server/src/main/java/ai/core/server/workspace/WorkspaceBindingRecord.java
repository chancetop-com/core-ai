package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * Current runtime pointer of a workspace and the hub's bound session. A runtime is replaceable;
 * this row is what tells a new server pod where the volume is currently mounted and who owns it.
 *
 * @author stephen
 */
public class WorkspaceBindingRecord {
    public static WorkspaceBindingRecord create(String workspaceId, String sandboxId, String provider, long runtimeGeneration) {
        var now = ZonedDateTime.now();
        var binding = new WorkspaceBindingRecord();
        binding.workspaceId = workspaceId;
        binding.sandboxId = sandboxId;
        binding.provider = provider;
        binding.runtimeGeneration = runtimeGeneration;
        binding.boundAt = now;
        binding.updatedAt = now;
        return binding;
    }

    public String workspaceId;
    public String sandboxId;
    public String provider;
    public long runtimeGeneration;
    public String podId;
    // null means the runtime exists but no session is bound yet
    public String boundSessionId;
    public ZonedDateTime runtimeStartedAt;
    public ZonedDateTime boundAt;
    public ZonedDateTime updatedAt;

}
