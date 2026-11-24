package ai.core.server.workspace;

/**
 * Resource profile of the workspace runtime. Deliberately separate from {@code SandboxConfig},
 * whose chat defaults clamp memory to 2 GiB and CPU to 2 cores; a workspace build needs more.
 *
 * @author stephen
 */
public enum WorkspaceProfile {
    SMALL("workspace-small", 2048, 1000),
    DEFAULT("workspace-default", 4096, 2000),
    BUILD("workspace-build", 8192, 4000);

    public final String id;
    public final int memoryMb;
    public final int cpuMillicores;

    WorkspaceProfile(String id, int memoryMb, int cpuMillicores) {
        this.id = id;
        this.memoryMb = memoryMb;
        this.cpuMillicores = cpuMillicores;
    }

    public static WorkspaceProfile of(String id) {
        if (id == null || id.isBlank()) return DEFAULT;
        for (var profile : values()) {
            if (profile.id.equalsIgnoreCase(id.trim())) return profile;
        }
        throw new IllegalArgumentException("unknown workspace profile: " + id);
    }
}
