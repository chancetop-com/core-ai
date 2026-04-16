package ai.core.server.sandbox.kubernetes;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.SandboxClient;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * @author stephen
 */
public class KubernetesSandbox implements Sandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesSandbox.class);

    private final String podName;
    private final SandboxClient runtimeClient;
    private volatile SandboxStatus status = SandboxStatus.READY;
    private final Instant createdAt;

    public KubernetesSandbox(String podName, String podIp, int timeoutSeconds) {
        this.podName = podName;
        this.runtimeClient = new SandboxClient(podIp, SandboxConstants.RUNTIME_PORT, timeoutSeconds);
        this.createdAt = Instant.now();
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
            LOGGER.error("sandbox execution failed: pod={}, tool={}", podName, toolName, e);
            return ToolCallResult.failed("Sandbox execution failed: " + e.getMessage())
                    .withStats("error", "sandbox_error");
        }
    }

    @Override
    public SandboxStatus getStatus() {
        return status;
    }

    @Override
    public String getId() {
        return podName;
    }

    @Override
    public void close() {
        status = SandboxStatus.TERMINATED;
        runtimeClient.close();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String getPodIp() {
        return runtimeClient.getIp();
    }
}
