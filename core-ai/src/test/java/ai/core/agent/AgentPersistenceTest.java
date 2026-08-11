package ai.core.agent;

import ai.core.context.Compression;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.persistence.PersistenceProvider;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AgentPersistenceTest {

    private static final String FIRST_MESSAGE = "Original first user message";

    private final AgentPersistence persistence = new AgentPersistence();

    @Test
    void roundTripPreservesMessagesHistoryAndStatus() {
        var agent = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        addConversation(agent);
        agent.updateNodeStatus(NodeStatus.RUNNING);

        var data = persistence.serialization(agent);

        var restored = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        persistence.deserialization(restored, data);

        assertEquals(agent.getMessages().size(), restored.getMessages().size());
        assertEquals(agent.getHistory().size(), restored.getHistory().size());
        assertEquals(FIRST_MESSAGE, restored.getHistory().getFirst().getTextContent());
        assertEquals(NodeStatus.RUNNING, restored.getNodeStatus());
    }

    @Test
    void legacyDataWithoutHistoryFallsBackToMessageBaseline() {
        var agent = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        addConversation(agent);
        var data = persistence.serialization(agent);
        var domain = JsonUtil.fromJson(AgentPersistence.AgentPersistenceDomain.class, data);
        domain.history = null;
        var legacyData = JsonUtil.toJson(domain);

        var restored = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        persistence.deserialization(restored, legacyData);

        // baseline = user/assistant text from messages, system and tool messages excluded
        assertEquals(3, restored.getHistory().size());
        assertEquals(FIRST_MESSAGE, restored.getHistory().getFirst().getTextContent());
        assertFalse(restored.getHistory().stream().anyMatch(m -> m.role == RoleType.SYSTEM));
    }

    @Test
    void firstUserMessageReadsFromHistory() {
        var agent = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        agent.addMessage(Message.of(RoleType.USER, "first"));
        agent.addMessage(Message.of(RoleType.ASSISTANT, "answer"));
        agent.addMessage(Message.of(RoleType.USER, "second"));

        var data = persistence.serialization(agent);

        assertEquals("first", AgentPersistence.firstUserMessage(data));
    }

    @Test
    void compressionKeepsSessionTitleStable() {
        var store = new InMemoryPersistenceProvider();
        var provider = new SummaryLLMProvider("Compressed summary");
        var agent = Agent.builder()
                .llmProvider(provider)
                .persistenceProvider(store)
                .build();
        agent.addMessage(Message.of(RoleType.SYSTEM, "system prompt"));
        for (int i = 0; i < 10; i++) {
            agent.addMessage(Message.of(RoleType.USER, "User message " + i));
            agent.addMessage(Message.of(RoleType.ASSISTANT, "Assistant response " + i));
        }
        agent.addMessage(Message.of(RoleType.USER, "Final user message"));

        var compression = new Compression(0.0001, 2, provider, "test-model", "test-model");
        var compressed = compression.forceCompress(agent.getMessages());
        assertTrue(compressed.size() < agent.getMessages().size());
        agent.getMessages().clear();
        agent.getMessages().addAll(compressed);

        agent.save("session-1");
        var data = store.load("session-1").orElseThrow();

        // the title (first user message) must survive compression
        assertEquals("User message 0", AgentPersistence.firstUserMessage(data));
        assertEquals("Final user message", agent.getHistory().getLast().getTextContent());
    }

    @Test
    void historyOnlyKeepsUserAndAssistantText() {
        var agent = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        agent.addMessage(Message.of(RoleType.SYSTEM, "system prompt"));
        agent.addMessage(Message.of(RoleType.USER, "hello"));
        var toolCall = FunctionCall.of("call-1", "function", "search", "{}");
        agent.addMessage(Message.of(RoleType.ASSISTANT, null, null, null, List.of(toolCall)));
        agent.addMessage(Message.of(RoleType.TOOL, "tool result", "search", "call-1", null));
        agent.addMessage(Message.of(RoleType.ASSISTANT, "the answer"));

        assertEquals(2, agent.getHistory().size());
        assertEquals("hello", agent.getHistory().get(0).getTextContent());
        assertEquals("the answer", agent.getHistory().get(1).getTextContent());
    }

    @Test
    void hasUserMessageIgnoresInterruptionMarkerOnlySessions() {
        var agent = Agent.builder().llmProvider(new SummaryLLMProvider("summary")).build();
        assertFalse(agent.hasUserMessage());

        agent.addMessage(Message.of(RoleType.USER,
                "<system-reminder>The user interrupted the previous action. Do not continue what you were doing.</system-reminder>"));
        assertFalse(agent.hasUserMessage());

        agent.addMessage(Message.of(RoleType.USER, "real question"));
        assertTrue(agent.hasUserMessage());
    }

    private void addConversation(Agent agent) {
        agent.addMessage(Message.of(RoleType.SYSTEM, "system prompt"));
        agent.addMessage(Message.of(RoleType.USER, FIRST_MESSAGE));
        agent.addMessage(Message.of(RoleType.ASSISTANT, "assistant reply"));
        agent.addMessage(Message.of(RoleType.USER, "follow-up"));
    }

    static class SummaryLLMProvider extends LLMProvider {
        private final String summary;

        SummaryLLMProvider(String summary) {
            super(new LLMProviderConfig("test-model", 0.7, null));
            this.summary = summary;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            return CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, summary))),
                    new Usage());
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            return null;
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            return null;
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            return null;
        }

        @Override
        public String name() {
            return "summary-llm";
        }
    }

    static class InMemoryPersistenceProvider implements PersistenceProvider {
        private String content;

        @Override
        public void save(String id, String context) {
            this.content = context;
        }

        @Override
        public void clear() {
            this.content = null;
        }

        @Override
        public void delete(List<String> ids) {
            this.content = null;
        }

        @Override
        public Optional<String> load(String id) {
            return content == null ? Optional.empty() : Optional.of(content);
        }
    }
}
