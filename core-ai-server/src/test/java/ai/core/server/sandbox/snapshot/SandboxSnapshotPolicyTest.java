package ai.core.server.sandbox.snapshot;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.settings.SystemSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxSnapshotPolicyTest {
    private SandboxSnapshotPolicy policy;
    private TestSystemSettingsService settings;
    private ObjectStorageServiceResolver storageResolver;
    private ObjectStorageService storage;

    @BeforeEach
    void setUp() {
        policy = new SandboxSnapshotPolicy();
        settings = new TestSystemSettingsService();
        storageResolver = mock(ObjectStorageServiceResolver.class);
        storage = mock(ObjectStorageService.class);
        policy.settings = settings;
        policy.storageResolver = storageResolver;
    }

    @Test
    void effectiveRequiresAllThreeConditions() {
        settings.requestedEnabled = true;
        when(storageResolver.resolve()).thenReturn(storage);
        policy.configure(true);

        assertEquals(new SandboxSnapshotPolicy.Status(true, true, true, true), policy.status());

        policy.configure(false);
        assertEquals(new SandboxSnapshotPolicy.Status(true, false, true, false), policy.status());
    }

    @Test
    void readsRequestedStateOnEveryDecision() {
        when(storageResolver.resolve()).thenReturn(storage);
        policy.configure(true);

        assertFalse(policy.status().effective());
        settings.requestedEnabled = true;
        assertTrue(policy.status().effective());
        assertEquals(2, settings.readCount);
    }

    @Test
    void storageResolutionFailureFailsClosed() {
        settings.requestedEnabled = true;
        when(storageResolver.resolve()).thenThrow(new IllegalStateException("storage unavailable"));
        policy.configure(true);

        assertEquals(new SandboxSnapshotPolicy.Status(true, true, false, false), policy.status());
    }

    @Test
    void settingsReadFailureFailsRequestedStateClosedButRetainsCleanupStorage() {
        settings.readFailure = new IllegalStateException("settings unavailable");
        when(storageResolver.resolve()).thenReturn(storage);
        policy.configure(true);

        var decision = policy.decision();

        assertEquals(new SandboxSnapshotPolicy.Status(false, true, true, false), decision.status());
        assertSame(storage, decision.storage());
    }

    @Test
    void decisionRetainsResolvedStorageInstance() {
        settings.requestedEnabled = true;
        when(storageResolver.resolve()).thenReturn(storage);
        policy.configure(true);

        assertSame(storage, policy.decision().storage());
    }

    @Test
    void statusEffectiveAlwaysMatchesItsExposedConditions() {
        var storageReady = new AtomicBoolean();
        when(storageResolver.resolve()).thenAnswer(invocation -> storageReady.get() ? storage : null);

        var cases = new boolean[][]{
                {false, false, false},
                {false, false, true},
                {false, true, false},
                {false, true, true},
                {true, false, false},
                {true, false, true},
                {true, true, false},
                {true, true, true}
        };
        for (var values : cases) {
            settings.requestedEnabled = values[0];
            policy.configure(values[1]);
            storageReady.set(values[2]);

            var status = policy.status();

            assertEquals(status.requestedEnabled() && status.deploymentAllowed() && status.storageReady(),
                    status.effective());
        }
    }

    private static final class TestSystemSettingsService extends SystemSettingsService {
        boolean requestedEnabled;
        int readCount;
        RuntimeException readFailure;

        @Override
        public boolean sandboxSnapshotEnabled() {
            readCount++;
            if (readFailure != null) throw readFailure;
            return requestedEnabled;
        }
    }
}
