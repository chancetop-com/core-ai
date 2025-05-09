package ai.core;

import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.AzureInferenceProvider;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.persistence.PersistenceProviderType;
import ai.core.persistence.PersistenceProviders;
import ai.core.persistence.providers.FilePersistenceProvider;
import ai.core.persistence.providers.RedisPersistenceProvider;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import ai.core.rag.VectorStoreType;
import ai.core.rag.VectorStores;
import ai.core.rag.vectorstore.hnswlib.HnswConfig;
import ai.core.rag.vectorstore.hnswlib.HnswLibVectorStore;
import ai.core.rag.vectorstore.milvus.MilvusConfig;
import ai.core.rag.vectorstore.milvus.MilvusVectorStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class MultiAgentModule extends Module {
    @Override
    protected void initialize() {
        configPersistenceProvider();
        configVectorStore();
        configLLMProvider();
    }

    private void configPersistenceProvider() {
        var providers = new PersistenceProviders();
        bind(providers);
        configRedisPersistenceProvider(providers);
        configTemporaryPersistenceProvider(providers);
        configFilePersistenceProvider(providers);
    }

    private void configVectorStore() {
        var vectorStores = new VectorStores();
        bind(vectorStores);
        configMilvus(vectorStores);
        configHnswLib(vectorStores);
    }

    private void configLLMProvider() {
        var providers = new LLMProviders();
        bind(providers);
        configLiteLLM(providers, setupLLMProperties(LLMProviderType.LITELLM));
        configAzureInference(providers, setupLLMProperties(LLMProviderType.AZURE_INFERENCE));
        configDeepSeek(providers, setupLLMProperties(LLMProviderType.DEEPSEEK));
        configAzureOpenAI(providers, setupLLMProperties(LLMProviderType.AZURE));
        configOpenAI(providers, setupLLMProperties(LLMProviderType.OPENAI));
    }

    private void configOpenAI(LLMProviders providers, LLMProviderConfig config) {
        property("openai.api.key").ifPresent(key -> {
            var provider = new AzureOpenAIProvider(config, key, null);
            bind(provider);
            providers.addProvider(LLMProviderType.OPENAI, provider);
        });
    }

    private void configAzureOpenAI(LLMProviders providers, LLMProviderConfig config) {
        property("azure.api.key").ifPresent(key -> property("azure.api.base").ifPresent(base -> {
            var provider = new AzureOpenAIProvider(config, key, base);
            bind(provider);
            providers.addProvider(LLMProviderType.AZURE, provider);
        }));
    }

    private void configAzureInference(LLMProviders providers, LLMProviderConfig config) {
        property("azure.ai.api.key").ifPresent(key -> property("azure.ai.api.base").ifPresent(base -> {
            var provider = new AzureInferenceProvider(config, key, base, true);
            bind(provider);
            providers.addProvider(LLMProviderType.AZURE_INFERENCE, provider);
        }));
    }

    private void configDeepSeek(LLMProviders providers, LLMProviderConfig config) {
        property("deepseek.api.key").ifPresent(key -> {
            var provider = new AzureInferenceProvider(config, key, "https://api.deepseek.com/v1", false);
            bind(provider);
            providers.addProvider(LLMProviderType.DEEPSEEK, provider);
        });
    }

    private void configLiteLLM(LLMProviders providers, LLMProviderConfig config) {
        property("litellm.api.base").ifPresent(base -> {
            load(new LiteLLMModule());
            var provider = new LiteLLMProvider(config);
            bind(provider);
            bind(LiteLLMImageProvider.class);
            providers.addProvider(LLMProviderType.LITELLM, provider);
        });
    }

    private LLMProviderConfig setupLLMProperties(LLMProviderType type) {
        var config = new LLMProviderConfig(LLMProviders.getProviderDefaultChatModel(type), 0.7d, "text-embedding-3-large");
        property("llm.temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        property("llm.model").ifPresent(config::setModel);
        property("llm.embeddings.model").ifPresent(config::setEmbeddingModel);
        return config;
    }

    private void configRedisPersistenceProvider(PersistenceProviders providers) {
        property("sys.redis.host").ifPresent(host -> {
            var provider = new RedisPersistenceProvider();
            bind(provider);
            providers.addPersistenceProvider(PersistenceProviderType.REDIS, provider);
        });
    }

    private void configFilePersistenceProvider(PersistenceProviders providers) {
        property("sys.persistence.file.directory").ifPresent(dir -> {
            var provider = new FilePersistenceProvider(dir);
            bind(provider);
            providers.addPersistenceProvider(PersistenceProviderType.FILE, provider);
        });
    }

    private void configTemporaryPersistenceProvider(PersistenceProviders providers) {
        var provider = new TemporaryPersistenceProvider();
        bind(provider);
        providers.addPersistenceProvider(PersistenceProviderType.TEMPORARY, provider);
    }

    private void configHnswLib(VectorStores vectorStores) {
        property("sys.hnswlib.path").ifPresent(path -> {
            var vectorStore = new HnswLibVectorStore(HnswConfig.of(path));
            bind(vectorStore);
            vectorStores.addVectorStore(VectorStoreType.HNSW_LIB, vectorStore);
        });
    }

    private void configMilvus(VectorStores vectorStores) {
        property("sys.milvus.uri").ifPresent(uri -> {
            var config = MilvusConfig.builder()
                    .uri(uri)
                    .token(property("sys.milvus.token").orElse(null))
                    .database(property("sys.milvus.database").orElse(null))
                    .username(property("sys.milvus.username").orElse(null))
                    .password(property("sys.milvus.password").orElse(null)).build();
            var vectorStore = new MilvusVectorStore(config);
            bind(vectorStore);
            vectorStores.addVectorStore(VectorStoreType.MILVUS, vectorStore);
        });
    }
}
