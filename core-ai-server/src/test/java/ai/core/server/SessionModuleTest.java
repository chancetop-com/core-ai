package ai.core.server;

import ai.core.server.sandbox.terminal.SandboxTerminalService;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionRegistry;
import core.framework.module.APIConfig;
import core.framework.module.HTTPConfig;
import core.framework.module.SchedulerConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SessionModuleTest {
    @Test
    void bindsSessionRegistryBeforeRuntimeConsumers() {
        var module = new RecordingSessionModule();

        module.initializeForTest();

        int registryIndex = module.boundClasses.indexOf(SessionRegistry.class);
        int messageServiceIndex = module.boundClasses.indexOf(ChatMessageService.class);
        assertTrue(registryIndex >= 0, "SessionModule must bind SessionRegistry");
        assertTrue(registryIndex < messageServiceIndex,
                "SessionRegistry must be bound before ChatMessageService because dependency injection is eager");
    }

    /**
     * Regression guard for the cross-service HMAC key mismatch fixed in fix round 1:
     * SessionModule.parseTicketSecret must treat a hex-LOOKING secret as raw UTF-8 bytes,
     * never hex-decode it, because the Go terminal gateway (core-ai-terminal-gateway/main.go)
     * always uses TICKET_SECRET's raw string bytes as the HMAC key. This exercises the actual
     * SessionModule wiring path (not just SandboxTerminalService directly), so it fails if the
     * hex-decode branch is ever reintroduced at the one place it previously lived.
     */
    @Test
    void wiresTicketSecretAsRawUtf8BytesNeverHexDecoded() {
        var module = new TicketSecretCapturingSessionModule();

        module.initializeForTest();

        assertArrayEquals("aabbccdd".getBytes(StandardCharsets.UTF_8), module.capturedService.ticketSecret);
    }

    private static final class RecordingSessionModule extends SessionModule {
        private final List<Class<?>> boundClasses = new ArrayList<>();

        private void initializeForTest() {
            super.initialize();
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            boundClasses.add(instanceClass);
            return mock(instanceClass);
        }

        // SessionModule also binds pre-built instances (e.g. new SessionActivityRegistry(...),
        // the SandboxTerminalService) rather than only class tokens; record the runtime type so
        // the ordering assertion still sees a harmless entry for them.
        @Override
        public <T> T bind(T instance) {
            boundClasses.add(instance.getClass());
            return instance;
        }

        @Override
        public <T> T bean(Class<T> instanceClass) {
            return mock(instanceClass);
        }

        // registerSandboxTerminal() reads sys.sandbox.terminal.enabled; no real ModuleContext
        // exists in this test, so report the property as absent (gate stays disabled).
        @Override
        public Optional<String> property(String key) {
            return Optional.empty();
        }

        @Override
        public SchedulerConfig schedule() {
            return mock(SchedulerConfig.class);
        }

        @Override
        public HTTPConfig http() {
            return mock(HTTPConfig.class);
        }

        @Override
        public APIConfig api() {
            return mock(APIConfig.class);
        }
    }

    private static final class TicketSecretCapturingSessionModule extends SessionModule {
        private static final Map<String, String> PROPERTIES = Map.of(
                "sys.sandbox.terminal.enabled", "true",
                "sys.sandbox.terminal.ticketSecret", "aabbccdd",
                "sys.sandbox.terminal.gatewayUrl", "wss://terminal.example.com");

        private SandboxTerminalService capturedService;

        private void initializeForTest() {
            super.initialize();
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            return mock(instanceClass);
        }

        @Override
        public <T> T bind(T instance) {
            if (instance instanceof SandboxTerminalService service) {
                capturedService = service;
            }
            return instance;
        }

        @Override
        public <T> T bean(Class<T> instanceClass) {
            return mock(instanceClass);
        }

        @Override
        public Optional<String> property(String key) {
            return Optional.ofNullable(PROPERTIES.get(key));
        }

        @Override
        public SchedulerConfig schedule() {
            return mock(SchedulerConfig.class);
        }

        @Override
        public HTTPConfig http() {
            return mock(HTTPConfig.class);
        }

        @Override
        public APIConfig api() {
            return mock(APIConfig.class);
        }
    }
}
