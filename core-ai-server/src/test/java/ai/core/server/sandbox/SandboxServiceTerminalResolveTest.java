package ai.core.server.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminal runtime resolution must never mutate sandbox lifecycle state: no reattach/beginEpoch,
 * no sessionSandboxes write, no Redis write, no provider.acquire/release, no close of an attached
 * sandbox. These tests pin the pure decision table plus that safety property concretely.
 *
 * @author Xander
 */
class SandboxServiceTerminalResolveTest {

    @Test
    void missingBindingReportsMissing() {
        var result = SandboxTerminalRuntimeResolver.decideTerminalRuntime(null, "sb-1");
        assertEquals(SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.MISSING, result);
    }

    @Test
    void differentBindingReportsReplaced() {
        var result = SandboxTerminalRuntimeResolver.decideTerminalRuntime("sb-2", "sb-1");
        assertEquals(SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.REPLACED, result);
    }

    @Test
    void matchingBindingIsCurrent() {
        var result = SandboxTerminalRuntimeResolver.decideTerminalRuntime("sb-1", "sb-1");
        assertEquals(SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.CURRENT, result);
    }

    // Load-bearing safety test: the redis-less test constructor has no binding, so
    // resolveTerminalRuntime must report MISSING without ever touching the provider's
    // acquire/release lifecycle or the session sandbox map.
    @Test
    void resolveNeverTouchesSnapshotEpochOrSessionMap() {
        var provider = new StubProvider("sb-1", "10.0.0.5", 8080);
        var service = new SandboxService(provider, SandboxService.createDefaultConfig());
        var resolver = new SandboxTerminalRuntimeResolver(service);

        var runtime = resolver.resolveTerminalRuntime("session-1", "sb-1", "user-1");

        assertEquals(SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.MISSING, runtime.status());
        assertNull(runtime.ip());
        assertEquals(0, runtime.port());
        assertEquals(0, provider.acquireCount.get());
        assertEquals(0, provider.attachCount.get());
        assertEquals(0, provider.releaseCount.get());
        assertNull(service.sessionSandbox("session-1"));
    }

    // The attachTransient path itself (exercised directly on SandboxManager, since the
    // redis-less service constructor never produces a CURRENT decision) must resolve an
    // address with zero lifecycle bookkeeping: no map registration, no acquire/release call.
    @Test
    void attachTransientResolvesAddressWithNoLifecycleTracking() {
        var provider = new StubProvider("sb-1", "10.0.0.5", 8080);
        var manager = new SandboxManager(provider);

        var attached = manager.attachTransient("sb-1", SandboxService.createDefaultConfig(), "session-1", "user-1");

        assertTrue(attached.isPresent());
        assertEquals("10.0.0.5", attached.get().ip());
        assertEquals(8080, attached.get().port());
        assertEquals(1, provider.attachCount.get());
        assertEquals(0, provider.acquireCount.get());
        assertEquals(0, provider.releaseCount.get());
        assertEquals(0, manager.activeCount());
        assertNull(manager.get("sb-1"));
    }

    /** Records acquire/attach/release calls; attach returns a fixed StubSandbox for the configured id. */
    private static final class StubProvider implements SandboxProvider {
        final AtomicInteger acquireCount = new AtomicInteger();
        final AtomicInteger attachCount = new AtomicInteger();
        final AtomicInteger releaseCount = new AtomicInteger();
        private final String knownSandboxId;
        private final String ip;
        private final int port;

        StubProvider(String knownSandboxId, String ip, int port) {
            this.knownSandboxId = knownSandboxId;
            this.ip = ip;
            this.port = port;
        }

        @Override
        public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
            acquireCount.incrementAndGet();
            throw new UnsupportedOperationException("resolver must never acquire a new sandbox");
        }

        @Override
        public Optional<Sandbox> attach(String sandboxId, SandboxConfig config, String sessionId, String userId) {
            attachCount.incrementAndGet();
            if (!knownSandboxId.equals(sandboxId)) return Optional.empty();
            return Optional.of(new StubSandbox(sandboxId, ip, port));
        }

        @Override
        public void release(Sandbox sandbox) {
            releaseCount.incrementAndGet();
        }

        @Override
        public SandboxStatus getStatus(Sandbox sandbox) {
            return SandboxStatus.READY;
        }
    }

    /** Fixed-address sandbox; throws on any lifecycle/execution method the resolver must never call. */
    private static final class StubSandbox implements Sandbox {
        private final String id;
        private final String ip;
        private final int port;

        StubSandbox(String id, String ip, int port) {
            this.id = id;
            this.ip = ip;
            this.port = port;
        }

        @Override
        public boolean shouldIntercept(String toolName) {
            return false;
        }

        @Override
        public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
            throw new UnsupportedOperationException("resolver must never execute in the attached sandbox");
        }

        @Override
        public SandboxStatus getStatus() {
            return SandboxStatus.READY;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String hostname() {
            return "stub-host";
        }

        @Override
        public void materializeSkill(String name, String version, byte[] tarBytes) {
            throw new UnsupportedOperationException("resolver must never materialize a skill");
        }

        @Override
        public SandboxFile downloadFile(String path) {
            throw new UnsupportedOperationException("resolver must never download a file");
        }

        @Override
        public void uploadFile(String path, byte[] content) {
            throw new UnsupportedOperationException("resolver must never upload a file");
        }

        @Override
        public String ip() {
            return ip;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public String image() {
            return "stub-image";
        }

        @Override
        public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
            throw new UnsupportedOperationException("resolver must never start an mcp server");
        }

        @Override
        public void stopMcpServer(String id) {
            throw new UnsupportedOperationException("resolver must never stop an mcp server");
        }

        @Override
        public String getMcpEndpoint() {
            throw new UnsupportedOperationException("resolver must never fetch the mcp endpoint");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("resolver must never close the attached sandbox");
        }
    }
}
