package ai.core.server.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.SandboxEventType;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxStatus;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * @author stephen
 */
public class LazySandbox implements Sandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(LazySandbox.class);

    private final SandboxConfig config;
    private final SandboxManager manager;
    private final Consumer<SandboxEvent> eventDispatcher;
    private final String sessionId;
    private final String userId;
    private volatile Sandbox delegate;
    private volatile SandboxStatus status = SandboxStatus.PENDING;

    public LazySandbox(SandboxConfig config, SandboxManager manager, Consumer<SandboxEvent> eventDispatcher, String sessionId, String userId) {
        this.config = config;
        this.manager = manager;
        this.eventDispatcher = eventDispatcher;
        this.sessionId = sessionId;
        this.userId = userId;
    }

    @Override
    public boolean shouldIntercept(String toolName) {
        // If sandbox is not ready, return false - will be created on execute
        var current = delegate;
        if (current == null) {
            return SandboxConstants.INTERCEPTED_TOOLS.contains(toolName);
        }
        return current.shouldIntercept(toolName);
    }

    @Override
    public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
        ensureReady();
        return delegate.execute(toolName, arguments, context);
    }

    @Override
    public void materializeSkill(String name, String version, byte[] tarBytes) {
        ensureReady();
        delegate.materializeSkill(name, version, tarBytes);
    }

    @Override
    public SandboxFile downloadFile(String path) {
        ensureReady();
        return delegate.downloadFile(path);
    }

    @Override
    public SandboxStatus getStatus() {
        var current = delegate;
        if (current == null) {
            return status;
        }
        return current.getStatus();
    }

    @Override
    public String getId() {
        var current = delegate;
        if (current == null) {
            return "pending";
        }
        return current.getId();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (delegate != null) {
                try {
                    manager.release(delegate);
                    dispatchEvent(SandboxEventType.TERMINATED);
                } finally {
                    delegate = null;
                    status = SandboxStatus.TERMINATED;
                }
            }
        }
    }

    private void ensureReady() {
        var current = delegate;
        if (current != null && current.getStatus() != SandboxStatus.ERROR) {
            return; // Fast path - sandbox is ready
        }

        synchronized (this) {
            // Double-check after acquiring lock
            current = delegate;
            if (current != null && current.getStatus() != SandboxStatus.ERROR) {
                return;
            }

            // Replace failed sandbox or create first one
            if (current != null) {
                dispatchEvent(SandboxEventType.REPLACING);
                manager.release(current);
            }

            dispatchEvent(SandboxEventType.CREATING);

            var startTime = System.currentTimeMillis();
            delegate = manager.acquire(config, sessionId, userId);
            var durationMs = System.currentTimeMillis() - startTime;
            dispatchEvent(SandboxEventType.READY, durationMs);
            LOGGER.info("sandbox ready: id={}, duration={}ms", delegate.getId(), durationMs);
        }
    }

    private void dispatchEvent(SandboxEventType type) {
        dispatchEvent(type, null);
    }

    private void dispatchEvent(SandboxEventType type, Long durationMs) {
        if (eventDispatcher == null) return;

        try {
            var sandboxId = delegate != null ? delegate.getId() : "pending";
            var event = switch (type) {
                case CREATING -> SandboxEvent.creating(sessionId, sandboxId);
                case READY -> SandboxEvent.ready(sessionId, sandboxId, durationMs != null ? durationMs : 0L);
                case ERROR -> SandboxEvent.error(sessionId, sandboxId, "Sandbox error");
                case REPLACING -> SandboxEvent.replacing(sessionId, sandboxId);
                case TERMINATED -> SandboxEvent.terminated(sessionId, sandboxId);
            };
            eventDispatcher.accept(event);
        } catch (Exception e) {
            LOGGER.warn("failed to dispatch sandbox event: type={}", type, e);
        }
    }
}
