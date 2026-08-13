package ai.core.server;

import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionRegistry;
import core.framework.module.APIConfig;
import core.framework.module.HTTPConfig;
import core.framework.module.SchedulerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

        @Override
        public <T> T bean(Class<T> instanceClass) {
            return mock(instanceClass);
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
