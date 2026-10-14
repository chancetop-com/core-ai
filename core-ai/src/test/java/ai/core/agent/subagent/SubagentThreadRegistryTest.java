package ai.core.agent.subagent;

import ai.core.agent.Agent;
import ai.core.agent.NodeStatus;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.persistence.PersistenceProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SubagentThreadRegistryTest {

    private static Agent agentWith(String... textMessages) {
        var agent = Agent.builder().name("test-agent").llmProvider(new MockLLMProvider()).build();
        var messages = new ArrayList<Message>();
        messages.add(Message.of(RoleType.SYSTEM, "you are a test agent"));
        for (var text : textMessages) {
            messages.add(Message.of(text.startsWith("assistant:") ? RoleType.ASSISTANT : RoleType.USER, text.replaceFirst("^(assistant|user):", "")));
        }
        agent.restorePersistedState(messages, null);
        return agent;
    }

    private static Agent newAgent() {
        return Agent.builder().name("test-agent").llmProvider(new MockLLMProvider()).build();
    }

    private static SubagentThread runOnce(SubagentThreadRegistry registry, String taskId, Agent agent) {
        var thread = registry.register(taskId, SubagentThreadKind.TASK, "explore", "a task", null, agent);
        assertTrue(registry.tryAcquire(thread));
        registry.release(thread, agent, "some summary");
        return thread;
    }

    @Test
    void gateRejectsParallelRunsAndRecoversAfterRelease() {
        var registry = new SubagentThreadRegistry("sess-gate", null);
        var thread = registry.register("sa-1", SubagentThreadKind.TASK, "explore", "d", null, newAgent());

        assertTrue(registry.tryAcquire(thread));
        assertFalse(registry.tryAcquire(thread), "a running thread must reject a second run");
        registry.release(thread, thread.agent(), "done");
        assertTrue(registry.tryAcquire(thread), "a released thread accepts the next run");
        registry.abort(thread);
        assertEquals(1, thread.runCount(), "abort must not count a run");
    }

    @Test
    void persistAndRehydrateRestoreMessagesWithoutSystemPrompt() {
        var persistence = new MapPersistence();
        var registry = new SubagentThreadRegistry("sess-p", persistence);
        var agent = agentWith("user:find the auth code", "assistant:it is in Auth.java");
        var thread = runOnce(registry, "sa-p1", agent);
        registry.persist(thread);
        assertEquals(1, thread.runCount());
        assertTrue(thread.contextTokens() > 0);

        var reloaded = new SubagentThreadRegistry("sess-p", persistence);
        var dormant = reloaded.find("sa-p1").orElseThrow();
        assertEquals(SubagentThreadState.DORMANT, dormant.state());
        assertEquals(1, dormant.runCount());
        assertEquals("explore", dormant.type());
        assertEquals("a task", dormant.description());

        var fresh = newAgent();
        var rehydrated = reloaded.rehydrate(dormant, () -> fresh);
        assertEquals(fresh, rehydrated);
        assertEquals(2, fresh.getMessages().size(), "the persisted system message must be dropped, the rest restored");
        assertEquals(RoleType.USER, fresh.getMessages().getFirst().role);
        assertEquals(NodeStatus.INITED, fresh.getNodeStatus());
        assertEquals(SubagentThreadState.LIVE, dormant.state());
    }

    @Test
    void rehydrateFailsWhenSavedContextIsMissing() {
        var registry = new SubagentThreadRegistry("sess-miss", new MapPersistence());
        var thread = registry.register("sa-miss", SubagentThreadKind.TASK, "explore", "d", null, null);

        var error = assertThrows(SubagentThreadUnavailableException.class, () -> registry.rehydrate(thread, SubagentThreadRegistryTest::newAgent));

        assertTrue(error.getMessage().contains("saved context"), error.getMessage());
        assertNull(thread.agent(), "a failed rehydrate must leave the thread dormant");
    }

    @Test
    void evictionDropsLeastRecentAgentAndKeepsItRecoverable() throws Exception {
        var persistence = new MapPersistence();
        var registry = new SubagentThreadRegistry("sess-evict", persistence, 1);
        var first = runOnce(registry, "sa-old", agentWith("user:one", "assistant:two"));
        Thread.sleep(5);
        var second = runOnce(registry, "sa-new", agentWith("user:three", "assistant:four"));

        registry.evictIfNeeded();

        assertNull(first.agent(), "the least recent thread must be dropped");
        assertEquals(SubagentThreadState.DORMANT, first.state());
        assertNotNull(second.agent(), "the most recent thread must stay in memory");
        assertNotNull(persistence.load(SubagentPersistenceKeys.thread("sess-evict", "sa-old")).orElse(null),
                "an evicted thread must be persisted first");
    }

    @Test
    void listReturnsThreadsMostRecentlyActiveFirst() throws Exception {
        var registry = new SubagentThreadRegistry("sess-list", null);
        runOnce(registry, "sa-1", newAgent());
        Thread.sleep(5);
        runOnce(registry, "sa-2", newAgent());

        var list = registry.list();

        assertEquals(2, list.size());
        assertEquals("sa-2", list.getFirst().taskId());
    }

    @Test
    void persistenceKeysArePathSafe() {
        assertEquals("sess~sub~_index", SubagentPersistenceKeys.index("sess"));
        assertEquals("sess~sub~agent-my-agent", SubagentPersistenceKeys.thread("sess", "agent:my-agent"));
        assertEquals("sess~sub~a-b-c", SubagentPersistenceKeys.thread("sess", "a b/c"));
    }

    @Test
    void restoreCountersSurviveReload() {
        var persistence = new MapPersistence();
        var registry = new SubagentThreadRegistry("sess-c", persistence);
        var thread = runOnce(registry, "sa-c", agentWith("user:hi", "assistant:hello"));
        registry.persist(thread);

        var reloaded = new SubagentThreadRegistry("sess-c", persistence).find("sa-c").orElseThrow();

        assertEquals(thread.runCount(), reloaded.runCount());
        assertEquals(thread.contextTokens(), reloaded.contextTokens());
        assertTrue(reloaded.contextTokens() > 0);
        assertTrue(thread.createdAtMs() > 0);
        assertEquals(thread.createdAtMs(), reloaded.createdAtMs());
    }

    private static final class MapPersistence implements PersistenceProvider {
        private final Map<String, String> store = new TreeMap<>();

        @Override
        public void save(String id, String context) {
            store.put(id, context);
        }

        @Override
        public void clear() {
            store.clear();
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public Optional<String> load(String id) {
            return Optional.ofNullable(store.get(id));
        }
    }
}
