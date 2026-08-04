package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.persistence.PersistenceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class RunFailurePersistenceTest {

    @Test
    void llmFailurePersistsUserMessageBeforeErrorEvent() throws InterruptedException {
        // no responses configured -> the mock provider throws like a sudden LLM error
        var provider = new MockLLMProvider();
        var store = new InMemoryPersistenceProvider();
        var agent = Agent.builder()
                .llmProvider(provider)
                .persistenceProvider(store)
                .build();

        var session = new InProcessAgentSession("test-failure", agent, true, new InMemoryToolPermissionStore());
        var errorLatch = new CountDownLatch(1);
        var errorMessage = new AtomicReference<String>();
        session.onEvent(new AgentEventListener() {
            @Override
            public void onError(ErrorEvent event) {
                errorMessage.set(event.message);
                errorLatch.countDown();
            }
        });

        session.sendMessage("hello");
        assertTrue(errorLatch.await(10, TimeUnit.SECONDS), "error event should be dispatched");
        assertNotNull(errorMessage.get());

        var saved = store.load("test-failure");
        assertTrue(saved.isPresent(), "session should be persisted after run failure");
        assertTrue(saved.get().contains("hello"), "persisted session should contain the failed turn user message");

        session.close();
    }

    static class InMemoryPersistenceProvider implements PersistenceProvider {
        private String content;

        @Override
        public void save(String id, String context) {
            content = context;
        }

        @Override
        public void clear() {
            content = null;
        }

        @Override
        public void delete(List<String> ids) {
            content = null;
        }

        @Override
        public Optional<String> load(String id) {
            return Optional.ofNullable(content);
        }
    }
}
