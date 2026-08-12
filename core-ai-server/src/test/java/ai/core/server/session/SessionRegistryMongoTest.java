package ai.core.server.session;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.workflow.WorkflowTestModule;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class SessionRegistryMongoTest {
    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    SessionRegistry registry;

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    @Test
    void createdSessionStoresArraysThatAcceptSkillUpdates() {
        var sessionId = "created-" + UUID.randomUUID();
        registry.create(new SessionRegistry.SessionRegistration(
                sessionId, "user-1", "agent-1", "chat", null, null));

        registry.addLoadedSkillIds(sessionId, List.of("skill-1", "skill-1"));

        var stored = chatSessionCollection.get(sessionId).orElseThrow();
        assertEquals(List.of(), stored.loadedTools);
        assertEquals(List.of("skill-1"), stored.loadedSkillIds);
        assertEquals(List.of(), stored.loadedSubAgentIds);
    }

    @Test
    void toolRefsArePersistedByAtomicSessionUpdate() {
        var sessionId = "tool-ref-" + UUID.randomUUID();
        registry.create(new SessionRegistry.SessionRegistration(
                sessionId, "user-1", "agent-1", "chat", null, null));
        var toolRef = ToolRef.of("mcp-tool:server:search", ToolSourceType.MCP, "server");

        registry.addLoadedTools(sessionId, List.of(toolRef));

        var stored = chatSessionCollection.get(sessionId).orElseThrow();
        assertEquals(List.of(toolRef), stored.loadedTools);
    }

    @Test
    void legacyNullSkillListIsRepairedBeforeSkillUpdate() {
        var session = new ChatSession();
        session.id = "legacy-" + UUID.randomUUID();
        session.userId = "user-1";
        session.agentId = "agent-1";
        session.source = "chat";
        session.messageCount = 0L;
        session.createdAt = ZonedDateTime.now();
        chatSessionCollection.insert(session);

        registry.addLoadedSkillIds(session.id, List.of("skill-1"));

        assertEquals(List.of("skill-1"), chatSessionCollection.get(session.id).orElseThrow().loadedSkillIds);
    }
}
