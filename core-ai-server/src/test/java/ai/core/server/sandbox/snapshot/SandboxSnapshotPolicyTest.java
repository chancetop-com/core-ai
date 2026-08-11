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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxSnapshotPolicyTest {
    private SandboxSnapshotPolicy policy;
    private SystemSettingsService settings;
    private ObjectStorageServiceResolver storageResolver;
    private ObjectStorageService storage;

    @BeforeEach
    void setUp() {
        policy = new SandboxSnapshotPolicy();
        settings = mock(SystemSettingsService.class);
        storageResolver = mock(ObjectStorageServiceResolver.class);
        storage = mock(ObjectStorageService.class);
        policy.settings = settings;
        policy.storageResolver = storageResolver;
    }

    @Test
    void effectiveRequiresAllThreeConditions() {
        when(settings.sandboxSnapshotEnabled()).thenReturn(true);
        when(storageResolver.resolve()).thenReturn(storage);
        policy.configure(true);

        assertEquals(new SandboxSnapshotPolicy.Status(true, true, true, true), policy.status());

        policy.configure(false);
        assertEquals(new SandboxSnapshotPolicy.Status(true, false, true, false), policy.status());
    }

    @Test
    void readsRequestedStateOnEveryDecision() {
        when(storageResolver.resolve()).thenReturn(storage);
        when(settings.sandboxSnapshotEnabled()).thenReturn(false, true);
        policy.configure(true);

        assertFalse(policy.status().effective());
        assertTrue(policy.status().effective());
        verify(settings, times(2)).sandboxSnapshotEnabled();
    }

    @Test
    void storageResolutionFailureFailsClosed() {
        when(settings.sandboxSnapshotEnabled()).thenReturn(true);
        when(storageResolver.resolve()).thenThrow(new IllegalStateException("storage unavailable"));
        policy.configure(true);

        assertEquals(new SandboxSnapshotPolicy.Status(true, true, false, false), policy.status());
    }

    @Test
    void decisionRetainsResolvedStorageInstance() {
        when(settings.sandboxSnapshotEnabled()).thenReturn(true);
        when(storageResolver.resolve()).thenReturn(storage);
        policy.configure(true);

        assertSame(storage, policy.decision().storage());
    }

    @Test
    void statusEffectiveAlwaysMatchesItsExposedConditions() {
        var requested = new AtomicBoolean();
        var storageReady = new AtomicBoolean();
        when(settings.sandboxSnapshotEnabled()).thenAnswer(invocation -> requested.get());
        when(storageResolver.resolve()).thenAnswer(invocation -> storageReady.get() ? storage : null);

        for (var requestedEnabled : new boolean[]{false, true}) {
            for (var deploymentAllowed : new boolean[]{false, true}) {
                for (var ready : new boolean[]{false, true}) {
                    requested.set(requestedEnabled);
                    storageReady.set(ready);
                    policy.configure(deploymentAllowed);

                    var status = policy.status();

                    assertEquals(status.requestedEnabled() && status.deploymentAllowed() && status.storageReady(),
                            status.effective());
                }
            }
        }
    }
}
