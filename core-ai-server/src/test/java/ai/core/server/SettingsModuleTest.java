package ai.core.server;

import ai.core.api.server.settings.SystemSettingsWebService;
import ai.core.server.web.SystemSettingsWebServiceImpl;
import core.framework.module.APIConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SettingsModuleTest {
    @Test
    void doesNotRegisterWebServiceBeforeSnapshotPolicyIsAvailable() {
        var module = new RecordingSettingsModule();

        module.initializeForTest();

        assertFalse(module.apiRequested);
        assertFalse(module.boundClasses.contains(SystemSettingsWebServiceImpl.class));
    }

    @Test
    void registersWebServiceInLateModule() {
        var module = new RecordingSystemSettingsWebModule();

        module.initializeForTest();

        verify(module.api).service(SystemSettingsWebService.class, module.webService);
    }

    private static final class RecordingSettingsModule extends SettingsModule {
        final List<Class<?>> boundClasses = new ArrayList<>();
        boolean apiRequested;

        void initializeForTest() {
            super.initialize();
        }

        @Override
        public Optional<String> property(String key) {
            return Optional.empty();
        }

        @Override
        public String requiredProperty(String key) {
            return "mongodb://localhost/core-ai";
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            boundClasses.add(instanceClass);
            return mock(instanceClass);
        }

        @Override
        public <T> T bind(T instance) {
            return instance;
        }

        @Override
        public APIConfig api() {
            apiRequested = true;
            return mock(APIConfig.class);
        }
    }

    private static final class RecordingSystemSettingsWebModule extends SystemSettingsWebModule {
        final APIConfig api = mock(APIConfig.class);
        final SystemSettingsWebServiceImpl webService = mock(SystemSettingsWebServiceImpl.class);

        void initializeForTest() {
            super.initialize();
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            return instanceClass.cast(webService);
        }

        @Override
        public APIConfig api() {
            return api;
        }
    }
}
