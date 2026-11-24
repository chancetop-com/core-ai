package ai.core.server.workspace;

/**
 * Everything the CR builder needs to render one workspace runtime. Kept free of DB rows so the
 * manifest can be unit-tested without a store.
 *
 * @author stephen
 */
public class WorkspaceRuntimeSpec {
    public String workspaceId;
    public String ownerUserId;
    public String image;
    public String pvcName;
    public long runtimeGeneration;
    public WorkspaceProfile profile = WorkspaceProfile.DEFAULT;
    public int maxRuntimeSeconds = WorkspaceDefaults.MAX_RUNTIME_SECONDS;
}
