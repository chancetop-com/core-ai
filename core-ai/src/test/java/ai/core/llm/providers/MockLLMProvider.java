package ai.core.llm.providers;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock LLM provider for testing purposes
 * <p>
 * This provider allows you to pre-define responses that will be returned in sequence.
 * It's useful for unit tests where you want to test agent behavior without making actual LLM API calls.
 * <p>
 * Example usage:
 * <pre>
 * MockLLMProvider mockProvider = new MockLLMProvider();
 *
 * // First response: LLM decides to call a function
 * mockProvider.addResponse(toolCallResponse);
 *
 * // Second response: LLM returns final answer
 * mockProvider.addResponse(finalResponse);
 *
 * Agent agent = Agent.builder()
 *     .llmProvider(mockProvider)
 *     .toolCalls(Functions.from(service, "method1", "method2"))
 *     .build();
 *
 * String result = agent.run("query", ExecutionContext.empty());
 * </pre>
 *
 * @author stephen
 */
public class MockLLMProvider extends LLMProvider {
    private final List<CompletionResponse> responses = new ArrayList<>();
    private int callCount = 0;

    /**
     * Creates a mock LLM provider with default configuration
     */
    public MockLLMProvider() {
        super(new LLMProviderConfig("mock-model", 0.7, "mock-embedding-model"));
    }

    /**
     * Creates a mock LLM provider with custom configuration
     *
     * @param config the LLM provider configuration
     */
    public MockLLMProvider(LLMProviderConfig config) {
        super(config);
    }

    /**
     * Add a response that will be returned by the next completion call
     *
     * @param response the completion response to return
     */
    public void addResponse(CompletionResponse response) {
        responses.add(response);
    }

    /**
     * Add multiple responses that will be returned in sequence
     *
     * @param responses the completion responses to return
     */
    public void addResponses(CompletionResponse... responses) {
        this.responses.addAll(List.of(responses));
    }

    /**
     * Reset the mock provider by clearing all responses and resetting the call count
     */
    public void reset() {
        responses.clear();
        callCount = 0;
    }

    /**
     * Get the number of times completion was called
     *
     * @return the call count
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * Get the total number of responses that have been added
     *
     * @return the total response count
     */
    public int getResponseCount() {
        return responses.size();
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        if (callCount >= responses.size()) {
            throw new RuntimeException("No more mock responses available. "
                    + "Expected " + callCount + " calls but only " + responses.size() + " responses were configured.");
        }
        return responses.get(callCount++);
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        return doCompletion(request);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        throw new UnsupportedOperationException("Embeddings not supported by MockLLMProvider");
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) {
        throw new UnsupportedOperationException("Rerankings not supported by MockLLMProvider");
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        throw new UnsupportedOperationException("Caption image not supported by MockLLMProvider");
    }

    @Override
    public int maxTokens() {
        return 4096;
    }

    @Override
    public String name() {
        return "mock-llm";
    }
}
