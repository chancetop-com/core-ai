package ai.core.server.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SandboxEvent;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxBinding;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.workspace.WorkspaceBindingRecord;
import ai.core.server.workspace.WorkspaceException;
import ai.core.server.workspace.WorkspaceService;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A session's handle on its durable workspace. The runtime behind it is replaceable: the first sandbox
 * operation wakes the workspace and attaches to whatever runtime the workspace currently owns. Releasing
 * this handle detaches the session and never deletes the workspace's runtime or volume; sleep is the only
 * operation that stops it.
 *
 * @author stephen
 */
public class WorkspaceSandbox implements Sandbox {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceSandbox.class);
    private static final String PENDING_ID = "pending";

    private final SandboxConfig config;
    private final String sessionId;
    private final String userId;
    private final String workspaceId;
    private final WorkspaceService workspaces;
    private final SandboxProvider provider;
    private final Consumer<SandboxEvent> eventDispatcher;
    private final Runnable postAttach;

    private final Object lock = new Object();
    private Sandbox delegate;
    private String sandboxId = PENDING_ID;
    private SandboxBinding pendingBinding;
    private boolean closed;

    public WorkspaceSandbox(SandboxConfig config, SessionRef session, WorkspaceService workspaces,
                            SandboxProvider provider, Consumer<SandboxEvent> eventDispatcher, Runnable postAttach) {
        this.config = config;
        this.sessionId = session.sessionId();
        this.userId = session.userId();
        this.workspaceId = session.workspaceId();
        this.workspaces = workspaces;
        this.provider = provider;
        this.eventDispatcher = eventDispatcher;
        this.postAttach = postAttach;
    }

    @Override
    public boolean shouldIntercept(String toolName) {
        return sandbox().shouldIntercept(toolName);
    }

    @Override
    public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
        return sandbox().execute(toolName, arguments, context);
    }

    @Override
    public SandboxStatus getStatus() {
        synchronized (lock) {
            if (closed) return SandboxStatus.TERMINATED;
            return delegate == null ? SandboxStatus.PENDING : delegate.getStatus();
        }
    }

    @Override
    public String getId() {
        synchronized (lock) {
            return sandboxId;
        }
    }

    @Override
    public String hostname() {
        synchronized (lock) {
            return delegate == null ? workspaceId : delegate.hostname();
        }
    }

    @Override
    public void materializeSkill(String name, String version, byte[] tarBytes) {
        sandbox().materializeSkill(name, version, tarBytes);
    }

    @Override
    public SandboxFile downloadFile(String path) {
        return sandbox().downloadFile(path);
    }

    @Override
    public void uploadFile(String path, byte[] content) {
        sandbox().uploadFile(path, content);
    }

    @Override
    public void uploadFile(String path, Path file) {
        sandbox().uploadFile(path, file);
    }

    @Override
    public String ip() {
        synchronized (lock) {
            return delegate == null ? null : delegate.ip();
        }
    }

    @Override
    public int port() {
        synchronized (lock) {
            return delegate == null ? 0 : delegate.port();
        }
    }

    @Override
    public String image() {
        synchronized (lock) {
            return delegate == null ? config.image : delegate.image();
        }
    }

    @Override
    public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
        return sandbox().startMcpServer(scopedMcpId(id), command, args, env, timeoutSeconds);
    }

    @Override
    public void stopMcpServer(String id) {
        sandbox().stopMcpServer(scopedMcpId(id));
    }

    @Override
    public String getMcpEndpoint() {
        return sandbox().getMcpEndpoint();
    }

    @Override
    public void bind(SandboxBinding binding) {
        synchronized (lock) {
            if (delegate != null) {
                delegate.bind(binding);
                return;
            }
            pendingBinding = binding;
        }
    }

    @Override
    public void unbind() {
        synchronized (lock) {
            pendingBinding = null;
            if (delegate != null) delegate.unbind();
        }
    }

    @Override
    public Boolean hubBound() {
        synchronized (lock) {
            if (delegate != null) return delegate.hubBound();
            return pendingBinding == null ? null : Boolean.FALSE;
        }
    }

    @Override
    public void close() {
        Sandbox current;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            current = delegate;
            delegate = null;
            pendingBinding = null;
        }
        if (current != null) {
            try {
                current.unbind();
            } catch (RuntimeException e) {
                LOGGER.debug("failed to clear the hub binding of the workspace runtime, session={}", sessionId, e);
            }
            try {
                current.close();
            } catch (RuntimeException e) {
                LOGGER.warn("failed to close the workspace sandbox client, session={}", sessionId, e);
            }
        }
        workspaces.detach(workspaceId, sessionId);
        LOGGER.info("workspace sandbox detached, session={}, workspace={}, runtime={}", sessionId, workspaceId, sandboxId);
    }

    private Sandbox sandbox() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("workspace sandbox is closed, session=" + sessionId);
            if (delegate == null) materialize();
            return delegate;
        }
    }

    private void materialize() {
        var startedAt = System.nanoTime();
        dispatch(SandboxEvent.creating(sessionId, PENDING_ID));
        WorkspaceBindingRecord binding;
        try {
            workspaces.wake(userId, workspaceId);
            binding = workspaces.bind(userId, workspaceId, sessionId);
        } catch (RuntimeException e) {
            dispatch(SandboxEvent.error(sessionId, PENDING_ID, "Workspace runtime failed to start"));
            throw e;
        }
        var attached = findRuntime(binding);
        if (attached == null) {
            dispatch(SandboxEvent.error(sessionId, PENDING_ID, "Workspace runtime failed to start"));
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED,
                    "workspace runtime is gone, workspace=" + workspaceId + ", runtime=" + binding.sandboxId);
        }
        sandboxId = binding.sandboxId;
        delegate = attached;
        if (pendingBinding != null) {
            attached.bind(pendingBinding);
            pendingBinding = null;
        }
        if (postAttach != null) postAttach.run();
        LOGGER.info("workspace runtime attached, session={}, workspace={}, runtime={}",
                sessionId, workspaceId, binding.sandboxId);
        dispatch(SandboxEvent.ready(sessionId, binding.sandboxId, (System.nanoTime() - startedAt) / 1_000_000,
                attached.hostname(), attached.ip(), attached.image()));
    }

    private Sandbox findRuntime(WorkspaceBindingRecord binding) {
        var attached = provider.attach(binding.sandboxId, config, sessionId, userId);
        return attached.orElse(null);
    }

    private String scopedMcpId(String id) {
        return sessionId + ":" + id;
    }

    private void dispatch(SandboxEvent event) {
        if (eventDispatcher == null) return;
        try {
            eventDispatcher.accept(event);
        } catch (RuntimeException e) {
            LOGGER.debug("sandbox event dispatch failed, session={}", sessionId, e);
        }
    }

    /** The durable identity a session handle carries: who runs, which session, which workspace. */
    public record SessionRef(String sessionId, String userId, String workspaceId) {
    }
}
