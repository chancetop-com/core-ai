package ai.core.server.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.SandboxEventType;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class LazySandbox implements Sandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(LazySandbox.class);

    private final SandboxConfig config;
    private final SandboxManager manager;
    private final Consumer<SandboxEvent> eventDispatcher;
    private final SessionIdentity identity;
    private final Runnable postAcquireHook;
    private final SandboxSnapshotService snapshotService;
    private volatile long snapshotEpoch;
    private volatile boolean snapshotDirty;
    private volatile Sandbox delegate;
    private volatile SandboxStatus status = SandboxStatus.PENDING;

    public LazySandbox(SandboxConfig config, SandboxManager manager, Consumer<SandboxEvent> eventDispatcher, SessionIdentity identity) {
        this(config, manager, eventDispatcher, identity, null, null);
    }

    public LazySandbox(SandboxConfig config, SandboxManager manager, Consumer<SandboxEvent> eventDispatcher, SessionIdentity identity, Runnable postAcquireHook) {
        this(config, manager, eventDispatcher, identity, postAcquireHook, null);
    }

    public LazySandbox(SandboxConfig config, SandboxManager manager, Consumer<SandboxEvent> eventDispatcher, SessionIdentity identity,
                       Runnable postAcquireHook, SandboxSnapshotService snapshotService) {
        this.config = config;
        this.manager = manager;
        this.eventDispatcher = eventDispatcher;
        this.identity = identity;
        this.postAcquireHook = postAcquireHook;
        this.snapshotService = snapshotService;
    }

    /** Construct with a pre-existing delegate that is already acquired and tracked by the manager. */
    public LazySandbox(Sandbox delegate, SandboxConfig config, SandboxManager manager, SandboxContext context) {
        this.config = config;
        this.manager = manager;
        this.eventDispatcher = context.eventDispatcher;
        this.identity = context.identity;
        this.postAcquireHook = context.postAcquireHook;
        this.snapshotService = context.snapshotService;
        this.delegate = delegate;
        this.status = delegate.getStatus();
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
        snapshotDirty = true;
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
    public void uploadFile(String path, byte[] content) {
        ensureReady();
        snapshotDirty = true;
        delegate.uploadFile(path, content);
    }

    @Override
    public String hostname() {
        var current = delegate;
        return current != null ? current.hostname() : "pending";
    }

    @Override
    public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
        ensureReady();
        return delegate.startMcpServer(id, command, args, env, timeoutSeconds);
    }

    @Override
    public void stopMcpServer(String id) {
        var current = delegate;
        if (current != null) {
            current.stopMcpServer(id);
        }
    }

    @Override
    public String getMcpEndpoint() {
        var current = delegate;
        if (current == null) {
            throw new IllegalStateException("sandbox not ready, cannot get MCP endpoint");
        }
        return current.getMcpEndpoint();
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
    public String ip() {
        var current = delegate;
        return current != null ? current.ip() : null;
    }

    @Override
    public int port() {
        var current = delegate;
        return current != null ? current.port() : 0;
    }

    @Override
    public String image() {
        var current = delegate;
        return current != null ? current.image() : null;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (delegate != null) {
                try {
                    if (isDelegateTracked()) {
                        manager.release(delegate);
                    } else {
                        // Sandbox was already removed from manager by cleanupExpired() or a prior release.
                        // Do not call manager.release() again — the provider resource is already gone.
                        // The delegate reference is still non-null, so we just discard it here.
                        LOGGER.debug("sandbox delegate already released, skipping manager.release: id={}", delegate.getId());
                    }
                    dispatchEvent(SandboxEventType.TERMINATED);
                } finally {
                    delegate = null;
                    status = SandboxStatus.TERMINATED;
                }
            }
        }
    }

    public void ensureReady() {
        var current = delegate;
        if (current != null && current.getStatus() == SandboxStatus.READY) {
            // Bump sandbox TTL so long-running agent loops don't trigger expiry mid-turn.
            // touch() only bumps in-memory createdAt on every call; it throttles the
            // provider-level renewal (K8s patch) to at most once per half-TTL.
            manager.touch(current.getId());
            return;
        }

        synchronized (this) {
            // Double-check after acquiring lock
            current = delegate;
            if (current != null && current.getStatus() == SandboxStatus.READY) {
                manager.touch(current.getId());
                return;
            }

            // Replace failed sandbox or create first one
            if (current != null) {
                dispatchEvent(SandboxEventType.REPLACING);
                manager.release(current);
            }

            dispatchEvent(SandboxEventType.CREATING);

            var startTime = System.currentTimeMillis();
            delegate = manager.acquire(config, identity.sessionId(), identity.userId());
            if (delegate == null) {
                dispatchEvent(SandboxEventType.ERROR);
                throw new IllegalStateException("sandbox acquire returned null");
            }
            var acquireDuration = System.currentTimeMillis() - startTime;
            LOGGER.info("sandbox acquired: id={}, duration={}ms", delegate.getId(), acquireDuration);

            var restoreWarning = restoreSnapshot();
            runPostAcquireHook();

            var totalDuration = System.currentTimeMillis() - startTime;
            dispatchEvent(SandboxEventType.READY, totalDuration, restoreWarning);
            LOGGER.info("sandbox ready: id={}, totalDuration={}ms (acquire={}ms)", delegate.getId(), totalDuration, acquireDuration);
        }
    }

    private void runPostAcquireHook() {
        if (postAcquireHook != null) {
            try {
                postAcquireHook.run();
            } catch (Exception e) {
                LOGGER.warn("post-acquire hook failed for session={}", identity.sessionId(), e);
            }
        }
    }

    /** Restore the latest snapshot before READY. Returns a warning message on degradation, else null. */
    private String restoreSnapshot() {
        if (snapshotService == null || !snapshotService.enabled()) return null;
        try {
            snapshotEpoch = snapshotService.beginEpoch(identity.sessionId());
            snapshotDirty = false;
            var outcome = snapshotService.restoreLatest(identity.sessionId(), identity.userId(), delegate.ip(), delegate.port());
            if (outcome == SandboxSnapshotService.RestoreOutcome.DEGRADED) {
                return "Sandbox is ready (previous work files could not be restored; starting from a clean environment)";
            }
        } catch (Exception e) {
            LOGGER.warn("snapshot restore hook failed, continuing with empty sandbox: session={}", identity.sessionId(), e);
        }
        return null;
    }

    private void dispatchEvent(SandboxEventType type) {
        dispatchEvent(type, null, null);
    }

    private void dispatchEvent(SandboxEventType type, Long durationMs, String readyMessage) {
        if (eventDispatcher == null) return;

        try {
            var sandboxId = delegate != null ? delegate.getId() : "pending";
            var hostname = delegate != null ? delegate.hostname() : null;
            var ip = delegate != null ? delegate.ip() : null;
            var image = delegate != null ? delegate.image() : null;
            var event = switch (type) {
                case CREATING -> SandboxEvent.creating(identity.sessionId(), sandboxId);
                case READY -> {
                    var readyEvent = SandboxEvent.ready(identity.sessionId(), sandboxId, durationMs != null ? durationMs : 0L, hostname, ip, image);
                    if (readyMessage != null) readyEvent.message = readyMessage;
                    yield readyEvent;
                }
                case ERROR -> SandboxEvent.error(identity.sessionId(), sandboxId, "Sandbox error");
                case REPLACING -> SandboxEvent.replacing(identity.sessionId(), sandboxId);
                case TERMINATED -> SandboxEvent.terminated(identity.sessionId(), sandboxId);
            };
            eventDispatcher.accept(event);
        } catch (Exception e) {
            LOGGER.warn("failed to dispatch sandbox event: type={}", type, e);
        }
    }

    public String sessionId() {
        return identity.sessionId();
    }

    public String userId() {
        return identity.userId();
    }

    public long snapshotEpoch() {
        return snapshotEpoch;
    }

    public boolean snapshotDirty() {
        return snapshotDirty;
    }

    /** True while the delegate is still tracked by the manager — i.e. not yet released by TTL cleanup.
     *  Guards snapshot capture against hitting a destroyed (possibly IP-recycled) sandbox. */
    public boolean isDelegateTracked() {
        var current = delegate;
        return current != null && manager.get(current.getId()) != null;
    }

    public record SessionIdentity(String sessionId, String userId) {
    }

    public record SandboxContext(Consumer<SandboxEvent> eventDispatcher, SessionIdentity identity, Runnable postAcquireHook, SandboxSnapshotService snapshotService) {
    }
}
