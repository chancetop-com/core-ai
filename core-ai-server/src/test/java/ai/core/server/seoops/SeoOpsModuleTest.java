package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsWebService;
import core.framework.module.APIConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author xander
 */
class SeoOpsModuleTest {
    @Test
    void disabledModuleDoesNotRegisterWebService() {
        var module = new RecordingModule(false);
        module.initializeForTest();
        assertFalse(module.apiRequested);
    }

    @Test
    void enabledModuleBindsConfigAndRegistersService() {
        var module = new RecordingModule(true);
        module.initializeForTest();
        assertTrue(module.apiRequested);
        assertEquals("agent-safe", module.config.copilotAgentId());
        verify(module.api).service(SeoOpsWebService.class, module.webService);
    }

    private static final class RecordingModule extends SeoOpsModule {
        final boolean enabled;
        final APIConfig api = mock(APIConfig.class);
        final SeoOpsWebServiceImpl webService = mock(SeoOpsWebServiceImpl.class);
        boolean apiRequested;
        SeoOpsRuntimeConfig config;

        private RecordingModule(boolean enabled) {
            this.enabled = enabled;
        }

        void initializeForTest() {
            super.initialize();
        }

        @Override
        public Optional<String> property(String key) {
            return switch (key) {
                case "sys.seoops.enabled" -> Optional.of(Boolean.toString(enabled));
                case "sys.seoops.copilot.agent-id" -> Optional.of("agent-safe");
                default -> Optional.empty();
            };
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            if (instanceClass == SeoOpsWebServiceImpl.class) return instanceClass.cast(webService);
            return mock(instanceClass);
        }

        @Override
        public <T> T bind(T instance) {
            if (instance instanceof SeoOpsRuntimeConfig runtimeConfig) config = runtimeConfig;
            return instance;
        }

        @Override
        public APIConfig api() {
            apiRequested = true;
            return api;
        }
    }
}
