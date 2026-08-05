package ai.core.server.run;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMCallExecutorTest {
    @Test
    void usesGatewayDefaultModelWhenDefinitionHasNoModel() {
        var executor = executor("deepseek-v4-flash", "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(null), "hello");

        assertEquals("deepseek-v4-flash", stub.captured.model);
    }

    @Test
    void fallsBackToSystemSettingsWhenGatewayHasNoDefaultModel() {
        var executor = executor(null, "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(null), "hello");

        assertEquals("deepseek/deepseek-v4-flash", stub.captured.model);
    }

    @Test
    void treatsBlankModelAsUnset() {
        var executor = executor("deepseek-v4-flash", "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition(" "), "hello");

        assertEquals("deepseek-v4-flash", stub.captured.model);
    }

    @Test
    void definitionModelTakesPrecedenceOverGatewayDefault() {
        var executor = executor("deepseek-v4-flash", "deepseek/deepseek-v4-flash");
        var stub = stub(executor);

        executor.execute(definition("gpt-4o"), "hello");

        assertEquals("gpt-4o", stub.captured.model);
    }

    private LLMCallExecutor executor(String gatewayDefaultModel, String systemSettingsModel) {
        var executor = new LLMCallExecutor();
        var gateway = mock(GatewayRoutingEngine.class);
        var settings = mock(SystemSettingsService.class);
        when(gateway.defaultChatModelId()).thenReturn(gatewayDefaultModel);
        when(settings.llmModel()).thenReturn(systemSettingsModel);
        executor.gatewayRoutingEngine = gateway;
        executor.systemSettingsService = settings;
        return executor;
    }

    private CapturingProvider stub(LLMCallExecutor executor) {
        var providers = mock(LLMProviders.class);
        var stub = new CapturingProvider();
        when(providers.getProvider()).thenReturn(stub);
        executor.llmProviders = providers;
        return stub;
    }

    private AgentDefinition definition(String model) {
        var definition = new AgentDefinition();
        definition.id = "llm-1";
        definition.userId = "user-1";
        definition.name = "test-llm-call";
        definition.type = DefinitionType.LLM_CALL;
        definition.model = model;
        definition.publishedConfig = null;
        definition.responseSchema = null;
        return definition;
    }

    private static final class CapturingProvider extends LLMProvider {
        CompletionRequest captured;

        CapturingProvider() {
            super(new LLMProviderConfig(null, null, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            captured = request;
            return response();
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            captured = request;
            return response();
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
            return "stub";
        }

        private CompletionResponse response() {
            return CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))),
                    new Usage(1, 1, 2)
            );
        }
    }
}
