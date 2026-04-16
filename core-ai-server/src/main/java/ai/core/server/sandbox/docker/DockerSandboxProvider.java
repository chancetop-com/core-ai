package ai.core.server.sandbox.docker;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxStatus;
import ai.core.sandbox.SandboxConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class DockerSandboxProvider implements SandboxProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerSandboxProvider.class);
    private static final String NETWORK_NAME = "core-ai-sandbox";
    private static final int RUNTIME_PORT = 8080;

    private final DockerClient dockerClient;
    private final SandboxConfig defaultConfig;
    private final String networkName;

    public DockerSandboxProvider(String socketPath, Path workspaceBase, SandboxConfig defaultConfig) {
        this(socketPath, workspaceBase, defaultConfig, NETWORK_NAME);
    }

    public DockerSandboxProvider(String socketPath, Path workspaceBase, SandboxConfig defaultConfig, String networkName) {
        this.dockerClient = new DockerClient(socketPath, workspaceBase);
        this.defaultConfig = defaultConfig != null ? defaultConfig : new SandboxConfig();
        this.networkName = networkName;
    }

    @Override
    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();

        var containerId = UUID.randomUUID().toString().substring(0, 8);
        var containerName = "sandbox-" + containerId;

        LOGGER.info("creating docker sandbox: name={}, image={}, sessionId={}", containerName, effectiveConfig.image, sessionId);

        containerId = null;
        try {
            // Create container
            var request = buildContainerRequest(effectiveConfig, containerName, sessionId, userId);
            containerId = dockerClient.createContainer(request);
            LOGGER.debug("container created: id={}", containerId);

            // Start container
            dockerClient.startContainer(containerId);
            LOGGER.debug("container started: id={}", containerId);

            // Wait for container to be running and get IP
            var ip = dockerClient.waitForRunning(containerId);
            LOGGER.info("docker sandbox ready: name={}, ip={}", containerName, ip);

            // Create sandbox with timeout based on config
            var timeoutSeconds = effectiveConfig.timeoutSeconds != null
                    ? effectiveConfig.timeoutSeconds
                    : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;
            return new DockerSandbox(containerId, ip, timeoutSeconds);
        } catch (Exception e) {
            LOGGER.error("failed to create docker sandbox: name={}", containerName, e);
            // Try to cleanup if partial creation happened
            cleanupContainer(containerName, containerId);
            throw new RuntimeException("Failed to create sandbox: " + e.getMessage(), e);
        }
    }

    private void cleanupContainer(String containerName, String containerId) {
        if (containerId == null) return;
        try {
            dockerClient.removeContainer(containerName);
        } catch (Exception cleanupError) {
            LOGGER.warn("failed to cleanup container after creation failure: name={}", containerName, cleanupError);
        }
    }

    @Override
    public void release(Sandbox sandbox) {
        if (sandbox == null) return;

        var sandboxId = sandbox.getId();
        LOGGER.info("releasing docker sandbox: id={}", sandboxId);

        try {
            dockerClient.removeContainer(sandboxId);
            sandbox.close();
            LOGGER.debug("docker sandbox released: id={}", sandboxId);
        } catch (Exception e) {
            LOGGER.error("failed to release docker sandbox: id={}", sandboxId, e);
            sandbox.close(); // Still close the sandbox even if remove fails
        }
    }

    @Override
    public SandboxStatus getStatus(Sandbox sandbox) {
        if (sandbox == null) return SandboxStatus.TERMINATED;

        try {
            var containerOpt = dockerClient.getContainer(sandbox.getId());
            if (containerOpt.isEmpty()) {
                return SandboxStatus.TERMINATED;
            }
            var container = containerOpt.get();
            var state = container.state != null ? container.state.status : null;

            return switch (state) {
                case "running" -> sandbox.getStatus() == SandboxStatus.EXECUTING
                        ? SandboxStatus.EXECUTING
                        : SandboxStatus.READY;
                case "exited", "dead" -> SandboxStatus.ERROR;
                case "created", "restarting" -> SandboxStatus.CREATING;
                case null, default -> sandbox.getStatus();
            };
        } catch (Exception e) {
            LOGGER.warn("failed to get container status: id={}", sandbox.getId(), e);
            return SandboxStatus.ERROR;
        }
    }

    private Map<String, Object> buildContainerRequest(SandboxConfig config, String containerName, String sessionId, String userId) {
        var request = new HashMap<String, Object>();

        // Basic container config
        request.put("Image", config.image != null ? config.image : SandboxConstants.DEFAULT_IMAGE);
        request.put("name", containerName);
        request.put("Labels", Map.of(
            "component", "sandbox",
            "session-id", sessionId != null ? sessionId : "unknown",
            "user-id", userId != null ? userId : "unknown"
        ));

        // Exposed ports
        var exposedPorts = new HashMap<String, Object>();
        exposedPorts.put(RUNTIME_PORT + "/tcp", new HashMap<>());
        request.put("ExposedPorts", exposedPorts);

        // Host config with resource limits
        var hostConfig = new HashMap<String, Object>();
        var memoryLimit = (config.memoryLimitMb != null ? config.memoryLimitMb : SandboxConstants.DEFAULT_MEMORY_LIMIT_MB) * 1024 * 1024L;
        var cpuLimit = config.cpuLimitMillicores != null ? config.cpuLimitMillicores : SandboxConstants.DEFAULT_CPU_LIMIT_MILLICORES;

        hostConfig.put("Memory", memoryLimit);
        hostConfig.put("CpuPeriod", 100000L);
        hostConfig.put("CpuQuota", cpuLimit * 1000L);
        hostConfig.put("AutoRemove", false);
        hostConfig.put("NetworkMode", networkName);

        // Port binding - map container port to random host port (required on Windows/macOS)
        var portBindings = new HashMap<String, Object>();
        portBindings.put(RUNTIME_PORT + "/tcp", List.of(Map.of("HostIp", "127.0.0.1", "HostPort", "")));
        hostConfig.put("PortBindings", portBindings);

        // Volume mounts - bind workspace and tmp
        var binds = new java.util.ArrayList<String>();
        var workspacePath = dockerClient.workspaceBase() != null
                ? dockerClient.workspaceBase().toAbsolutePath().toString().replace('\\', '/')
                : "/tmp/workspaces";
        binds.add(workspacePath + ":/workspace:ro");
        hostConfig.put("Binds", binds);

        // Mount tmpfs for /tmp (writable, size-limited)
        var tmpSizeBytes = parseSizeToBytes(config.tmpSizeLimit != null ? config.tmpSizeLimit : "100Mi");
        var tmpfs = Map.of("/tmp", "size=" + tmpSizeBytes);
        hostConfig.put("Tmpfs", tmpfs);

        // Security options
        hostConfig.put("ReadonlyRootfs", true);
        hostConfig.put("CapDrop", List.of("ALL"));
        hostConfig.put("SecurityOpt", List.of("no-new-privileges"));

        request.put("HostConfig", hostConfig);

        // Working directory
        request.put("WorkingDir", "/workspace");

        // Environment variables
        var maxAsync = config.maxAsyncTasks != null ? config.maxAsyncTasks : SandboxConstants.DEFAULT_MAX_ASYNC_TASKS;
        request.put("Env", List.of(
            "MAX_ASYNC_TASKS=" + maxAsync
        ));

        // Entrypoint - run the sandbox runtime
        request.put("Entrypoint", List.of("/usr/local/bin/core-ai-sandbox-runtime"));

        return request;
    }

    private long parseSizeToBytes(String size) {
        if (size.endsWith("Gi")) return Long.parseLong(size.replace("Gi", "")) * 1024 * 1024 * 1024;
        if (size.endsWith("Mi")) return Long.parseLong(size.replace("Mi", "")) * 1024 * 1024;
        if (size.endsWith("Ki")) return Long.parseLong(size.replace("Ki", "")) * 1024;
        return Long.parseLong(size);
    }
}
