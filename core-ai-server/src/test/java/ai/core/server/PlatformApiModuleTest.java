package ai.core.server;

import ai.core.server.web.CapabilitiesWebServiceImpl;
import core.framework.module.APIConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Locks the sandbox-terminal capability consistency rule: the exposed flag
 * must be enabled-property AND ticket-secret-configured AND gateway-url-configured,
 * never the raw {@code sys.sandbox.terminal.enabled} value alone -- otherwise the
 * frontend could see the feature as "on" while {@link ai.core.server.sandbox.terminal.SandboxTerminalService}
 * would reject every request because its own gate requires the same three conditions.
 *
 * @author xander
 */
class PlatformApiModuleTest {
    private static Map<String, String> properties(String... keyValuePairs) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    @Test
    void terminalFlagFalseWhenSecretMissingEvenIfEnabledFlagIsTrue() {
        var module = new RecordingPlatformApiModule(properties(
                "sys.sandbox.terminal.enabled", "true",
                "sys.sandbox.terminal.gatewayUrl", "wss://terminal.example.com"));
        // sys.sandbox.terminal.ticketSecret intentionally left unset

        module.initializeForTest();

        assertEquals(Boolean.FALSE, module.impl.get().sandboxTerminalEnabled);
    }

    @Test
    void terminalFlagFalseWhenGatewayUrlMissingEvenIfEnabledFlagIsTrue() {
        var module = new RecordingPlatformApiModule(properties(
                "sys.sandbox.terminal.enabled", "true",
                "sys.sandbox.terminal.ticketSecret", "shared-secret"));
        // sys.sandbox.terminal.gatewayUrl intentionally left unset

        module.initializeForTest();

        assertEquals(Boolean.FALSE, module.impl.get().sandboxTerminalEnabled);
    }

    @Test
    void terminalFlagTrueOnlyWhenEnabledSecretAndGatewayUrlAllConfigured() {
        var module = new RecordingPlatformApiModule(properties(
                "sys.sandbox.terminal.enabled", "true",
                "sys.sandbox.terminal.ticketSecret", "shared-secret",
                "sys.sandbox.terminal.gatewayUrl", "wss://terminal.example.com"));

        module.initializeForTest();

        assertEquals(Boolean.TRUE, module.impl.get().sandboxTerminalEnabled);
        // The gateway URL itself is still exposed (the frontend needs it); only the secret is withheld.
        assertEquals("wss://terminal.example.com", module.impl.get().sandboxTerminalGatewayUrl);
    }

    private static final class RecordingPlatformApiModule extends PlatformApiModule {
        private final Map<String, String> properties;
        private CapabilitiesWebServiceImpl impl;

        RecordingPlatformApiModule(Map<String, String> properties) {
            this.properties = properties;
        }

        void initializeForTest() {
            super.initialize();
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            if (instanceClass == CapabilitiesWebServiceImpl.class) {
                impl = new CapabilitiesWebServiceImpl();
                return instanceClass.cast(impl);
            }
            return mock(instanceClass);
        }

        @Override
        public Optional<String> property(String key) {
            return Optional.ofNullable(properties.get(key));
        }

        @Override
        public APIConfig api() {
            return mock(APIConfig.class);
        }
    }
}
