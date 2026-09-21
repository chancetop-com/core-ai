package ai.core.server.sandbox.agentsandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A sandbox whose config carries custom image/env is acquired as a direct Sandbox CR even when the
 * provider is configured for the warm pool. Every per-sandbox operation has to follow that per-sandbox
 * kind: dispatching on the provider configuration made status/release/renew/attach hit the claim API
 * with a CR name, which reported live sandboxes as TERMINATED (a replacement per idle tool call) and
 * made their release a silent no-op (orphaned sandboxes until TTL).
 *
 * @author stephen
 */
class AgentSandboxProviderTest {
    private static final String CR_ID = "core-ai-sandbox-abcd1234";
    private static final String CLAIM_ID = "claim-abcd1234";
    private static final String ASSIGNED_SANDBOX = "s55sx";

    private final AgentSandboxClient client = mock(AgentSandboxClient.class);
    private final AgentSandboxExtensionsClient extensionsClient = mock(AgentSandboxExtensionsClient.class);

    // --- status ---

    @Test
    void directSandboxStatusReadsCrWhenWarmPoolIsConfigured() {
        when(client.getSandbox(CR_ID)).thenReturn(Optional.of(readyCr()));

        assertEquals(SandboxStatus.READY, provider().getStatus(sandbox(AgentSandboxKind.DIRECT)));

        verify(client).getSandbox(CR_ID);
        verify(extensionsClient, never()).getClaim(anyString());
    }

    @Test
    void directSandboxStatusIsTerminatedWhenCrIsGone() {
        when(client.getSandbox(CR_ID)).thenReturn(Optional.empty());

        assertEquals(SandboxStatus.TERMINATED, provider().getStatus(sandbox(AgentSandboxKind.DIRECT)));
    }

    @Test
    void claimSandboxStatusReadsClaim() {
        when(extensionsClient.getClaim(CLAIM_ID)).thenReturn(Optional.of(readyClaim()));

        assertEquals(SandboxStatus.READY, provider().getStatus(sandbox(AgentSandboxKind.CLAIM)));

        verify(extensionsClient).getClaim(CLAIM_ID);
        verify(client, never()).getSandbox(anyString());
    }

    @Test
    void claimStatusKeepsLocalStateWithoutExtensionsClient() {
        // Warm pool switched off after the claim was handed out: an unverifiable sandbox must not be
        // reported as terminal, or the caller replaces a sandbox that is still running.
        var provider = providerWithoutExtensionsClient();

        assertEquals(SandboxStatus.READY, provider.getStatus(sandbox(AgentSandboxKind.CLAIM)));

        verify(client, never()).getSandbox(anyString());
    }

    // --- release ---

    @Test
    void directSandboxReleaseDeletesCrWhenWarmPoolIsConfigured() {
        var sandbox = sandbox(AgentSandboxKind.DIRECT);

        provider().release(sandbox);

        verify(client).deleteSandbox(CR_ID);
        verify(extensionsClient, never()).deleteClaim(anyString());
        assertEquals(SandboxStatus.TERMINATED, sandbox.getStatus());
    }

    @Test
    void claimSandboxReleaseDeletesClaim() {
        var sandbox = sandbox(AgentSandboxKind.CLAIM);

        provider().release(sandbox);

        verify(extensionsClient).deleteClaim(CLAIM_ID);
        verify(client, never()).deleteSandbox(anyString());
        assertEquals(SandboxStatus.TERMINATED, sandbox.getStatus());
    }

    @Test
    void releaseClosesSandboxEvenWhenProviderCallFails() {
        var sandbox = sandbox(AgentSandboxKind.DIRECT);
        doThrow(new RuntimeException("k8s unavailable")).when(client).deleteSandbox(CR_ID);

        provider().release(sandbox);

        assertEquals(SandboxStatus.TERMINATED, sandbox.getStatus());
    }

    // --- renew ---

    @Test
    void directSandboxRenewPatchesCrDeadline() {
        var config = new SandboxConfig();
        config.timeoutSeconds = null;

        provider().renew(sandbox(AgentSandboxKind.DIRECT), config);

        assertDeadlineWithin(client, CR_ID, 3600);
        verify(extensionsClient, never()).patchShutdownTime(anyString(), anyString());
    }

    @Test
    void claimSandboxRenewPatchesClaimDeadline() {
        var config = new SandboxConfig();
        config.timeoutSeconds = 1200;

        provider().renew(sandbox(AgentSandboxKind.CLAIM), config);

        assertDeadlineWithin(extensionsClient, CLAIM_ID, 1200);
        verify(client, never()).patchShutdownTime(anyString(), anyString());
    }

    @Test
    void renewSkipsClaimWithoutExtensionsClient() {
        var provider = providerWithoutExtensionsClient();

        provider.renew(sandbox(AgentSandboxKind.CLAIM), new SandboxConfig());

        verify(extensionsClient, never()).patchShutdownTime(anyString(), anyString());
        verifyNoInteractions(client);
    }

    // --- attach ---

    @Test
    void attachFindsDirectSandboxWhenWarmPoolIsConfigured() {
        when(client.getSandbox(CR_ID)).thenReturn(Optional.of(readyCr()));

        var attached = provider().attach(CR_ID, new SandboxConfig(), "session-1", "user-1");

        assertTrue(attached.isPresent());
        assertEquals(CR_ID, attached.get().getId());
        verify(client).getSandbox(CR_ID);
        verify(extensionsClient, never()).getClaim(anyString());
    }

    @Test
    void attachFindsClaimSandbox() {
        when(extensionsClient.getClaim(CLAIM_ID)).thenReturn(Optional.of(readyClaim()));

        var attached = provider().attach(CLAIM_ID, new SandboxConfig(), "session-1", "user-1");

        assertTrue(attached.isPresent());
        assertEquals(CLAIM_ID, attached.get().getId());
        verify(client, never()).getSandbox(anyString());
    }

    @Test
    void attachFallsBackToOtherProvisioningModeWhenPrimaryLookupMisses() {
        // A binding stored while the provider ran in warm pool mode must stay attachable after the
        // warm pool is reconfigured away: claims are gone, the CR is the only remaining handle.
        when(extensionsClient.getClaim(CLAIM_ID)).thenReturn(Optional.empty());
        when(client.getSandbox(CLAIM_ID)).thenReturn(Optional.of(readyCr(CLAIM_ID)));

        var attached = provider().attach(CLAIM_ID, new SandboxConfig(), "session-1", "user-1");

        assertTrue(attached.isPresent());
        verify(client).getSandbox(CLAIM_ID);
    }

    @Test
    void attachReturnsEmptyWhenSandboxNoLongerExists() {
        when(extensionsClient.getClaim(CR_ID)).thenReturn(Optional.empty());
        when(client.getSandbox(CR_ID)).thenReturn(Optional.empty());

        assertTrue(provider().attach(CR_ID, new SandboxConfig(), "session-1", "user-1").isEmpty());
    }

    // --- fixtures ---

    private AgentSandboxProvider provider() {
        var config = new AgentSandboxProviderConfig();
        config.client = client;
        config.extensionsClient = extensionsClient;
        config.templateName = "core-ai-sandbox";
        config.warmPoolName = "core-ai-sandbox";
        return new AgentSandboxProvider(config);
    }

    private AgentSandboxProvider providerWithoutExtensionsClient() {
        var config = new AgentSandboxProviderConfig();
        config.client = client;
        return new AgentSandboxProvider(config);
    }

    private AgentSandbox sandbox(AgentSandboxKind kind) {
        var id = kind == AgentSandboxKind.CLAIM ? CLAIM_ID : CR_ID;
        return new AgentSandbox(new AgentSandbox.Config(id, null, "10.0.0.7", 8080, 3600,
                SandboxConstants.DEFAULT_IMAGE, null, kind));
    }

    private AgentSandboxClient.SandboxCR readyCr() {
        return readyCr(CR_ID);
    }

    private AgentSandboxClient.SandboxCR readyCr(String name) {
        var cr = new AgentSandboxClient.SandboxCR();
        var condition = new AgentSandboxClient.Condition();
        condition.type = "Ready";
        condition.status = "True";
        cr.status = new AgentSandboxClient.SandboxStatus();
        cr.status.conditions = new AgentSandboxClient.Condition[]{condition};
        cr.status.podIPs = new String[]{"10.0.0.7"};
        cr.status.serviceFQDN = name + ".uat-ai.svc.cluster.local";
        return cr;
    }

    private AgentSandboxExtensionsClient.SandboxClaim readyClaim() {
        var condition = new AgentSandboxExtensionsClient.Condition();
        condition.type = "Ready";
        condition.status = "True";
        var ref = new AgentSandboxExtensionsClient.SandboxRef();
        ref.name = ASSIGNED_SANDBOX;
        ref.podIPs = new String[]{"10.0.0.8"};
        var claim = new AgentSandboxExtensionsClient.SandboxClaim();
        claim.status = new AgentSandboxExtensionsClient.SandboxClaimStatus();
        claim.status.conditions = new AgentSandboxExtensionsClient.Condition[]{condition};
        claim.status.sandbox = ref;
        return claim;
    }

    private void assertDeadlineWithin(AgentSandboxClient target, String name, int lifetimeSeconds) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(target).patchShutdownTime(eq(name), captor.capture());
        assertDeadline(captor.getValue(), lifetimeSeconds);
    }

    private void assertDeadlineWithin(AgentSandboxExtensionsClient target, String name, int lifetimeSeconds) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(target).patchShutdownTime(eq(name), captor.capture());
        assertDeadline(captor.getValue(), lifetimeSeconds);
    }

    private void assertDeadline(String shutdownTime, int lifetimeSeconds) {
        var deadline = Instant.parse(shutdownTime);
        var expected = Instant.now().plusSeconds(lifetimeSeconds);
        assertTrue(deadline.isAfter(expected.minusSeconds(60)) && deadline.isBefore(expected.plusSeconds(60)),
                "deadline " + deadline + " should sit within a minute of " + expected);
    }
}
