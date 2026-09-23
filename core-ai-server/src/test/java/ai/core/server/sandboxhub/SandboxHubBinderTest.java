package ai.core.server.sandboxhub;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxBinding;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxStatus;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hub identity lives in the runtime's memory, so a runtime that restarted behind a live sandbox
 * silently answers 503 to every script until someone binds it again. The binder must repair exactly
 * that state, and leave every other state alone: a bound runtime, an unreachable one, and a runtime
 * too old to know the endpoint at all.
 *
 * @author stephen
 */
class SandboxHubBinderTest {
    private static final byte[] SECRET = "session-token-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void rebindsWhenTheRuntimeLostItsBinding() {
        var sandbox = new StubSandbox(Boolean.FALSE);
        var binder = binder();

        binder.rebindIfRuntimeLost(sandbox, "http://core-ai-server:8080", "s-1", "u-1", "menu-agent", 3600);

        assertEquals(1, sandbox.bindings.size());
        assertEquals("s-1", sandbox.bindings.getFirst().sessionId());
    }

    @Test
    void leavesABoundRuntimeAlone() {
        var sandbox = new StubSandbox(Boolean.TRUE);

        binder().rebindIfRuntimeLost(sandbox, "http://core-ai-server:8080", "s-1", "u-1", "menu-agent", 3600);

        assertTrue(sandbox.bindings.isEmpty());
    }

    @Test
    void leavesAnUnreachableRuntimeAlone() {
        var sandbox = new StubSandbox(null);

        binder().rebindIfRuntimeLost(sandbox, "http://core-ai-server:8080", "s-1", "u-1", "menu-agent", 3600);

        assertTrue(sandbox.bindings.isEmpty());
        assertEquals(1, sandbox.probes);
    }

    @Test
    void stopsProbingARuntimeWithoutTheEndpoint() {
        var sandbox = new StubSandbox(Boolean.FALSE);
        sandbox.rejectBind = true;
        var binder = binder();

        binder.rebindIfRuntimeLost(sandbox, "http://core-ai-server:8080", "s-1", "u-1", "menu-agent", 3600);
        binder.rebindIfRuntimeLost(sandbox, "http://core-ai-server:8080", "s-1", "u-1", "menu-agent", 3600);

        assertEquals(1, sandbox.probes);
    }

    @Test
    void unconfiguredSecretBindsNothing() {
        var sandbox = new StubSandbox(Boolean.FALSE);
        var binder = new SandboxHubBinder(new SessionTokenService());

        binder.rebindIfRuntimeLost(sandbox, "http://core-ai-server:8080", "s-1", "u-1", "menu-agent", 3600);

        assertEquals(0, sandbox.probes);
    }

    private SandboxHubBinder binder() {
        var service = new SessionTokenService();
        service.secret = SECRET;
        return new SandboxHubBinder(service);
    }

    private static final class StubSandbox implements Sandbox {
        private final Boolean hubBound;
        private final List<SandboxBinding> bindings = new ArrayList<>();
        private int probes;
        private boolean rejectBind;

        private StubSandbox(Boolean hubBound) {
            this.hubBound = hubBound;
        }

        @Override
        public Boolean hubBound() {
            probes++;
            return hubBound;
        }

        @Override
        public void bind(SandboxBinding binding) {
            if (rejectBind) throw new UnsupportedOperationException("runtime predates /bind");
            bindings.add(binding);
        }

        @Override
        public void unbind() {
        }

        @Override
        public boolean shouldIntercept(String toolName) {
            return false;
        }

        @Override
        public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
            return ToolCallResult.completed("");
        }

        @Override
        public SandboxStatus getStatus() {
            return SandboxStatus.READY;
        }

        @Override
        public String getId() {
            return "sb-1";
        }

        @Override
        public String hostname() {
            return "sb-1";
        }

        @Override
        public void materializeSkill(String name, String version, byte[] tarBytes) {
        }

        @Override
        public SandboxFile downloadFile(String path) {
            return null;
        }

        @Override
        public void uploadFile(String path, byte[] content) {
        }

        @Override
        public String ip() {
            return "127.0.0.1";
        }

        @Override
        public int port() {
            return 8080;
        }

        @Override
        public String image() {
            return null;
        }

        @Override
        public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
            return id;
        }

        @Override
        public void stopMcpServer(String id) {
        }

        @Override
        public String getMcpEndpoint() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
