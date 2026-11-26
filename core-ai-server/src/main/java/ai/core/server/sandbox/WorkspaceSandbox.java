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
import ai.core.server.workspace.WorkspaceLeaseHeartbeat;
import ai.core.server.workspace.WorkspaceService;
import ai.core.server.workspace.WorkspaceTaskOutputs;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A session's handle on its durable workspace. The runtime behind it is replaceable: an operation wakes
 * the workspace when needed and attaches to whatever runtime the workspace currently owns. Tool and file
 * operations take the workspace's turn lease, so two sessions never write the same volume at once; the
 * lease of an idle session is released, and a runtime that was slept (or replaced) is re-attached on the
 * next operation. Releasing this handle detaches the session and never deletes the runtime or volume.
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
    private String leaseToken;
    private WorkspaceLeaseHeartbeat heartbeat;
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
        return runtime().shouldIntercept(toolName);
    }

    @Override
    public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
        WorkspaceLeaseHeartbeat active;
        Sandbox sandbox;
        synchronized (lock) {
            ensureTurn();
            active = heartbeat;
            sandbox = delegate;
        }
        var result = sandbox.execute(toolName, arguments, context);
        if (active != null && active.lost()) {
            // another session took the lease while this tool ran: its writes must not be reported as ours
            throw new WorkspaceException(WorkspaceException.REPLACED,
                    "workspace lease was lost during tool execution, workspace=" + workspaceId);
        }
        return result;
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
    public ToolCallResult pollTask(String taskId) {
        // polling reads runtime state only: no turn lease, but attach to the current runtime first
        return runtime().pollTask(taskId);
    }

    @Override
    public void materializeSkill(String name, String version, byte[] tarBytes) {
        turnSandbox().materializeSkill(name, version, tarBytes);
    }

    @Override
    public SandboxFile downloadFile(String path) {
        return turnSandbox().downloadFile(path);
    }

    @Override
    public void uploadFile(String path, byte[] content) {
        turnSandbox().uploadFile(path, content);
    }

    @Override
    public void uploadFile(String path, Path file) {
        turnSandbox().uploadFile(path, file);
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
        // MCP lifecycle is not an agent turn: it may start while another session holds the lease, and
        // its process ids are scoped by session so two sessions cannot replace each other's servers
        return runtime().startMcpServer(scopedMcpId(id), command, args, env, timeoutSeconds);
    }

    @Override
    public void stopMcpServer(String id) {
        runtime().stopMcpServer(scopedMcpId(id));
    }

    @Override
    public String getMcpEndpoint() {
        return runtime().getMcpEndpoint();
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
        String token;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            current = delegate;
            delegate = null;
            pendingBinding = null;
            stopHeartbeat();
            token = leaseToken;
            leaseToken = null;
        }
        workspaces.releaseLease(workspaceId, token);
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

    /** A workspace operation inside a turn: the runtime must be attached and this session must hold the lease. */
    private Sandbox turnSandbox() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("workspace sandbox is closed, session=" + sessionId);
            ensureTurn();
            return delegate;
        }
    }

    /** Runtime access outside a turn (MCP, readiness): no lease, but the attachment must be current. */
    private Sandbox runtime() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("workspace sandbox is closed, session=" + sessionId);
            ensureRuntime();
            return delegate;
        }
    }

    private void ensureTurn() {
        // verify the runtime only when this handle has nothing attached yet: an attached runtime is
        // trusted until the workspace row says it was slept (runtime_status becomes NONE)
        var turn = workspaces.beginTurn(userId, workspaceId, sessionId, delegate == null);
        var binding = turn.binding();
        if (delegate == null || !binding.sandboxId.equals(sandboxId)) {
            detachDelegate();
            attach(binding);
        } else if (postAttach != null && turn.rebound()) {
            // the hub's single binding belonged to another session; it must name this one before tools call back
            postAttach.run();
        }
        if (heartbeat == null || heartbeat.lost() || !turn.leaseToken().equals(leaseToken)) {
            stopHeartbeat();
            leaseToken = turn.leaseToken();
            heartbeat = workspaces.heartbeat(workspaceId, leaseToken);
        }
    }

    private void ensureRuntime() {
        var binding = workspaces.ensureRuntime(userId, workspaceId);
        if (delegate == null || !binding.sandboxId.equals(sandboxId)) {
            detachDelegate();
            attach(binding);
        }
    }

    private void attach(WorkspaceBindingRecord binding) {
        var startedAt = System.nanoTime();
        dispatch(SandboxEvent.creating(sessionId, PENDING_ID));
        var attached = provider.attach(binding.sandboxId, config, sessionId, userId).orElse(null);
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
        WorkspaceTaskOutputs.cleanup(attached);
        LOGGER.info("workspace runtime attached, session={}, workspace={}, runtime={}",
                sessionId, workspaceId, binding.sandboxId);
        dispatch(SandboxEvent.ready(sessionId, binding.sandboxId, (System.nanoTime() - startedAt) / 1_000_000,
                attached.hostname(), attached.ip(), attached.image()));
    }

    private void detachDelegate() {
        var current = delegate;
        delegate = null;
        sandboxId = PENDING_ID;
        if (current == null) return;
        try {
            current.unbind();
        } catch (RuntimeException e) {
            LOGGER.debug("failed to unbind the replaced workspace runtime, session={}", sessionId, e);
        }
        try {
            current.close();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to close the replaced workspace runtime client, session={}", sessionId, e);
        }
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.close();
            heartbeat = null;
        }
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
