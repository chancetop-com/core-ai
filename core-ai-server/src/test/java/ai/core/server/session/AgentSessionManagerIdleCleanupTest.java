package ai.core.server.session;

import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.web.sse.SessionChannelService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guard: cleanupIdleSessions must consult the cross-pod SessionActivityRegistry before
 * closing a locally idle session, since terminal traffic on a non-owner pod cannot
 * otherwise extend the session's life.
 */
@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
class AgentSessionManagerIdleCleanupTest {
    // Negative maxIdle pushes the threshold into the future, so every locally tracked
    // session looks idle regardless of when touchActivity actually ran.
    private static final Duration FORCE_IDLE = Duration.ofSeconds(-1);

    @Test
    void freshDurableActivityKeepsSessionOpenAndRenewsSandbox() {
        var manager = harness();
        when(manager.ownershipRegistry.isOwner("s1")).thenReturn(true);
        when(manager.sessionActivityRegistry.lastActivity("s1")).thenReturn(Long.MAX_VALUE);
        manager.touchActivity("s1");

        var closed = manager.cleanupIdleSessions(FORCE_IDLE);

        assertEquals(0, closed);
        verify(manager.sandboxService, times(2)).renewSandbox("s1"); // touchActivity + guard
        verify(manager.sandboxService, never()).releaseSandbox("s1");
        verify(manager.sessionAgentHelper, never()).releaseOwnership("s1");
    }

    @Test
    void staleDurableActivityClosesSessionAsBefore() {
        var manager = harness();
        when(manager.ownershipRegistry.isOwner("s1")).thenReturn(true);
        when(manager.sessionActivityRegistry.lastActivity("s1")).thenReturn(0L);
        manager.touchActivity("s1");

        var closed = manager.cleanupIdleSessions(FORCE_IDLE);

        assertEquals(1, closed);
        verify(manager.sandboxService).releaseSandbox("s1");
        verify(manager.sessionAgentHelper).releaseOwnership("s1");
    }

    @Test
    void nullRegistryDegradesToClosingIdleSessions() {
        var manager = harness();
        manager.sessionActivityRegistry = null;
        when(manager.ownershipRegistry.isOwner("s1")).thenReturn(true);
        manager.touchActivity("s1");

        var closed = manager.cleanupIdleSessions(FORCE_IDLE);

        assertEquals(1, closed);
        verify(manager.sandboxService).releaseSandbox("s1");
    }

    private AgentSessionManager harness() {
        var manager = new AgentSessionManager();
        manager.skillService = mock(SkillService.class);
        manager.mongoSkillProvider = mock(MongoSkillProvider.class);
        manager.skillArchiveBuilder = mock(SkillArchiveBuilder.class);
        manager.chatMessageService = mock(ChatMessageService.class);
        manager.sessionChannelService = mock(SessionChannelService.class);
        manager.sessionAgentHelper = mock(SessionAgentHelper.class);
        manager.sandboxService = mock(SandboxService.class);
        manager.ownershipRegistry = mock(SessionOwnershipRegistry.class);
        manager.sessionActivityRegistry = mock(SessionActivityRegistry.class);
        return manager;
    }
}
