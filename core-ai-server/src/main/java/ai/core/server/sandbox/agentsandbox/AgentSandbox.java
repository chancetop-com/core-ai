package ai.core.server.sandbox.agentsandbox;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.SandboxClient;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentSandbox implements Sandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandbox.class);

    private final String crName;
    private final String serviceName;
    private final String image;
    private final String podName;
    private final SandboxClient runtimeClient;
    private volatile SandboxStatus status = SandboxStatus.READY;
    private final Instant createdAt;

    public AgentSandbox(Config config) {
        this.crName = config.crName;
        this.serviceName = config.serviceName;
        this.image = config.image;
        this.podName = config.podName;
        // Use per-request timeout for HTTP calls, not the sandbox container TTL.
        // The container TTL (config.timeoutSeconds) controls K8s shutdownTime;
        // HTTP requests should not be bounded by the container lifetime.
        this.runtimeClient = new SandboxClient(config.host, config.port, SandboxConstants.REQUEST_TIMEOUT_SECONDS);
        this.createdAt = Instant.now();
    }

    public String serviceName() {
        return serviceName;
    }

    public void waitForReady() {
        runtimeClient.waitForReady(30_000);
    }

    @Override
    public boolean shouldIntercept(String toolName) {
        return SandboxConstants.INTERCEPTED_TOOLS.contains(toolName);
    }

    @Override
    public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
        try {
            return runtimeClient.execute(toolName, arguments, context);
        } catch (Exception e) {
            LOGGER.error("agent sandbox execution failed: cr={}, tool={}", crName, toolName, e);
            if (isConnectionError(e)) {
                status = SandboxStatus.ERROR;
            }
            return ToolCallResult.failed("Sandbox execution failed: " + e.getMessage())
                    .withStats("error", "sandbox_error");
        }
    }

    @Override
    public void materializeSkill(String name, String version, byte[] tarBytes) {
        runtimeClient.materializeSkill(name, version, tarBytes);
    }

    @Override
    public SandboxFile downloadFile(String path) {
        return runtimeClient.downloadFile(path);
    }

    @Override
    public void uploadFile(String path, byte[] content) {
        runtimeClient.uploadFile(path, content);
    }

    @Override
    public SandboxStatus getStatus() {
        return status;
    }

    @Override
    public String getId() {
        return crName;
    }

    @Override
    public String hostname() {
        return podName != null ? podName : crName;
    }

    @Override
    public String ip() {
        return runtimeClient.getIp();
    }

    @Override
    public int port() {
        return runtimeClient.getPort();
    }

    @Override
    public String image() {
        return image;
    }

    @Override
    public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
        var client = new SandboxClient(runtimeClient.getIp(), runtimeClient.getPort(), timeoutSeconds);
        try {
            return client.startMcpServer(id, command, args, env);
        } finally {
            client.close();
        }
    }

    @Override
    public void stopMcpServer(String id) {
        runtimeClient.stopMcpServer(id);
    }

    @Override
    public String getMcpEndpoint() {
        return runtimeClient.getMcpEndpoint();
    }

    @Override
    public void close() {
        status = SandboxStatus.TERMINATED;
        runtimeClient.close();
    }

    public Instant createdAt() {
        return createdAt;
    }

    private boolean isConnectionError(Throwable throwable) {
        var current = throwable;
        while (current != null) {
            if (current instanceof java.net.SocketException) return true;
            if (current instanceof java.net.SocketTimeoutException) {
                var message = current.getMessage();
                if (message != null && message.contains("Connect timed out")) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record Config(String crName, String serviceName, String host, int port, int timeoutSeconds, String image, String podName) { }
}
