package ai.core.server;

import ai.core.server.sandbox.snapshot.SandboxSnapshotPolicy;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import core.framework.module.SchedulerConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SandboxSnapshotModuleTest {
    @Test
    void configuresPolicyFromDeploymentProperty() {
        var module = new RecordingSandboxSnapshotModule();

        module.initializeForTest();

        verify(module.policy).configure(true);
    }

    private static final class RecordingSandboxSnapshotModule extends SandboxSnapshotModule {
        final SandboxSnapshotPolicy policy = mock(SandboxSnapshotPolicy.class);
        final SandboxSnapshotService service = mock(SandboxSnapshotService.class);
        final SchedulerConfig scheduler = mock(SchedulerConfig.class);

        void initializeForTest() {
            super.initialize();
        }

        @Override
        public Optional<String> property(String key) {
            return switch (key) {
                case "sys.sandbox.snapshot.enabled" -> Optional.of("true");
                case "azure.blob.snapshot.container", "storage.minio.snapshot.bucket" ->
                        Optional.of("sandbox-snapshots");
                default -> Optional.empty();
            };
        }

        @Override
        public <T> T bind(Class<T> instanceClass) {
            if (instanceClass == SandboxSnapshotPolicy.class) return instanceClass.cast(policy);
            if (instanceClass == SandboxSnapshotService.class) return instanceClass.cast(service);
            return mock(instanceClass);
        }

        @Override
        public SchedulerConfig schedule() {
            return scheduler;
        }
    }
}
