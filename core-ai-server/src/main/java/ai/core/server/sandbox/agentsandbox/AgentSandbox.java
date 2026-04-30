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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;

/**
 * @author stephen
 */
public class AgentSandbox implements Sandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandbox.class);

    private final String crName;
    private final String serviceName;
    private final SandboxClient runtimeClient;
    private volatile SandboxStatus status = SandboxStatus.READY;
    private final Instant createdAt;

    public AgentSandbox(String crName, String serviceName, String host, int port, int timeoutSeconds) {
        this.crName = crName;
        this.serviceName = serviceName;
        this.runtimeClient = new SandboxClient(host, port, timeoutSeconds);
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
    public SandboxStatus getStatus() {
        return status;
    }

    @Override
    public String getId() {
        return crName;
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
            if (current instanceof ConnectException || current instanceof SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }
}
