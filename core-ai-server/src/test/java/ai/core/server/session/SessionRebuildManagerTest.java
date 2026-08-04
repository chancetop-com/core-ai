package ai.core.server.session;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ChatSession;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRebuildManagerTest {
    @Test
    void agentSnapshotPersistsCaptionRoutingPreferenceAcrossPodRebuilds() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = new ChatSession();
        meta.id = "session-1";
        meta.userId = "user-1";
        meta.agentId = "agent-1";
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.name = "caption-agent";
        definition.preferCaptionPath = Boolean.TRUE;
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var manager = new SessionRebuildManager(new SessionRebuildManager.Deps(
                chatMessageService, agents, null, null, null, null, null, null,
                null, null, null, null, null, null, null));

        var state = manager.buildStateFromDb("session-1");

        var restored = SessionState.fromJson(state.toJson());
        assertEquals(Boolean.TRUE, restored.agentConfig.preferCaptionPath);
    }
}
