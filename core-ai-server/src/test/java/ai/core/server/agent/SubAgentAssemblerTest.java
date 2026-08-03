package ai.core.server.agent;

import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.registry.ToolRegistryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SubAgentAssemblerTest {
    private SubAgentAssembler assembler;
    private SystemSettingsService systemSettingsService;

    @BeforeEach
    void setUp() {
        assembler = new SubAgentAssembler();
        var providers = new LLMProviders();
        providers.addProvider(LLMProviderType.LITELLM, new StubLLMProvider());
        providers.setDefaultProvider(LLMProviderType.LITELLM);
        assembler.llmProviders = providers;
        assembler.persistenceProviders = new PersistenceProviders();
        systemSettingsService = mock(SystemSettingsService.class);
        assembler.systemSettingsService = systemSettingsService;
    }

    @Test
    void pinnedModelWithoutMultiModalModelFallsBackToSystemSetting() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("azure/responses/gpt-5-mini");
        var config = new SessionConfig();
        config.model = "deepseek/deepseek-v4-flash";

        var agent = assembler.buildAgent(buildConfig(config));

        assertEquals("azure/responses/gpt-5-mini", agent.getMultiModalModel());
    }

    @Test
    void explicitMultiModalModelWins() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("system-mm-model");
        var config = new SessionConfig();
        config.model = "deepseek/deepseek-v4-flash";
        config.multiModalModel = "explicit-mm-model";

        var agent = assembler.buildAgent(buildConfig(config));

        assertEquals("explicit-mm-model", agent.getMultiModalModel());
    }

    @Test
    void preferCaptionPathForcesCaptionRouting() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("system-mm-model");
        var config = new SessionConfig();
        config.model = "azure/gpt-5-mini";
        config.preferCaptionPath = Boolean.TRUE;

        var agent = assembler.buildAgent(buildConfig(config));

        org.junit.jupiter.api.Assertions.assertFalse(agent.getExecutionContext().isVisionNative());
    }

    @Test
    void nullConfigFallsBackToSystemSetting() {
        when(systemSettingsService.llmMultiModalModel()).thenReturn("system-mm-model");

        var agent = assembler.buildAgent(buildConfig(null));

        assertEquals("system-mm-model", agent.getMultiModalModel());
    }

    private SubAgentAssembler.BuildAgentConfig buildConfig(SessionConfig config) {
        return new SubAgentAssembler.BuildAgentConfig(config, ToolRegistryFactory.createEmpty(), null,
                "test-agent", null, null, null, null, null);
    }

    static class StubLLMProvider extends LLMProvider {
        StubLLMProvider() {
            super(new LLMProviderConfig("stub-model", 0.7, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public int maxTokens() {
            return 4096;
        }

        @Override
        public String name() {
            return "stub-llm";
        }
    }
}
