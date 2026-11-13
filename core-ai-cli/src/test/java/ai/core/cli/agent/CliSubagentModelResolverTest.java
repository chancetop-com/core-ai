package ai.core.cli.agent;

import ai.core.agent.subagent.SubagentModelUnavailableException;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.config.ModelRegistry;
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
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliSubagentModelResolverTest {
    private static LLMProviders providers(LLMProviderType... types) {
        var providers = new LLMProviders();
        for (var type : types) {
            providers.addProvider(type, new TestProvider(type.getName()));
        }
        return providers;
    }

    @Test
    void resolvesALocalModelToItsProvider() {
        var providers = providers(LLMProviderType.AZURE);
        var props = new PropertiesFileSource(new Properties());
        props.putProperty("azure.models", "azure-model");
        var resolver = new CliSubagentModelResolver(new ModelRegistry(providers, props), providers);

        var resolved = resolver.resolve("azure-model");

        assertEquals("azure-model", resolved.model());
        assertEquals("azure", resolved.providerName());
        assertSame(providers.getProvider(LLMProviderType.AZURE), resolved.provider());
    }

    @Test
    void prefersTheThreadProviderWhenItCanServeTheModel() {
        var providers = providers(LLMProviderType.AZURE, LLMProviderType.LITELLM);
        var props = new PropertiesFileSource(new Properties());
        props.putProperty("azure.models", "azure-model");
        var resolver = new CliSubagentModelResolver(new ModelRegistry(providers, props), providers);

        var resolved = resolver.resolve("azure-model", "litellm");

        assertEquals("litellm", resolved.providerName());
        assertSame(providers.getProvider(LLMProviderType.LITELLM), resolved.provider());
    }

    @Test
    void gatewayAcceptsAServerOnlyModel() {
        var providers = providers(LLMProviderType.LITELLM);
        var resolver = new CliSubagentModelResolver(new ModelRegistry(providers, new PropertiesFileSource(new Properties())), providers);

        var resolved = resolver.resolve("server-only-model");

        assertEquals("server-only-model", resolved.model());
        assertEquals("litellm", resolved.providerName());
    }

    @Test
    void unknownModelFailsWithoutAGateway() {
        var providers = providers(LLMProviderType.AZURE);
        var resolver = new CliSubagentModelResolver(new ModelRegistry(providers, new PropertiesFileSource(new Properties())), providers);

        var error = assertThrows(SubagentModelUnavailableException.class, () -> resolver.resolve("missing"));

        assertEquals(SubagentModelUnavailableException.Reason.UNKNOWN_MODEL, error.reason());
    }

    private static final class TestProvider extends LLMProvider {
        private final String name;

        private TestProvider(String name) {
            super(new LLMProviderConfig("default-model", 0.0, "embedding-model"));
            this.name = name;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String name() {
            return name;
        }
    }
}
