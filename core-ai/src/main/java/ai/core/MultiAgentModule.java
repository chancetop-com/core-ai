package ai.core;

import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.AzureInferenceProvider;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.persistence.providers.RedisPersistenceProvider;
import ai.core.rag.vectorstore.hnswlib.HnswConfig;
import ai.core.rag.vectorstore.hnswlib.HnswLibVectorStore;
import ai.core.rag.vectorstore.milvus.MilvusConfig;
import ai.core.rag.vectorstore.milvus.MilvusVectorStore;
import ai.core.task.TaskService;
import core.framework.module.Module;
import io.milvus.v2.client.MilvusClientV2;

/**
 * @author stephen
 */
public class MultiAgentModule extends Module {
    @Override
    protected void initialize() {
        this.property("sys.persistence").ifPresent(this::configPersistence);
        this.property("sys.vector.store").ifPresent(this::configVectorStore);
        configLLMProvider(requiredProperty("sys.llm.provider"));
        bindServices();
    }

    private void configVectorStore(String type) {
        if ("milvus".equals(type)) {
            configMilvus();
        }
        if ("hnswlib".equals(type)) {
            configHnswLib();
        }
    }

    private void configHnswLib() {
        bind(new HnswLibVectorStore(HnswConfig.of(requiredProperty("sys.hnswlib.path"))));
    }

    private void configLLMProvider(String type) {
        var config = setupLLMProperties();
        if ("litellm".equals(type)) {
            load(new LiteLLMModule());
            bind(new LiteLLMProvider(config));
            bind(LiteLLMImageProvider.class);
            return;
        }
        if ("azure-inference".equals(type)) {
            bind(new AzureInferenceProvider(config, requiredProperty("sys.llm.apikey"), property("sys.llm.endpoint").orElse(null)));
            return;
        }
        if ("azure".equals(type) || "openai".equals(type)) {
            bind(new AzureOpenAIProvider(config, requiredProperty("sys.llm.apikey"), property("sys.llm.endpoint").orElse(null)));
            return;
        }
        throw new RuntimeException("Unsupported LLM provider: " + type);
    }

    private LLMProviderConfig setupLLMProperties() {
        var config = new LLMProviderConfig("gpt-4o", 0.7d, "text-embedding-3-large");
        property("llm.temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        property("llm.model").ifPresent(config::setModel);
        property("llm.embeddings.model").ifPresent(config::setEmbeddingModel);
        return config;
    }

    private void configPersistence(String type) {
        if ("redis".equals(type)) {
            requiredProperty("sys.redis.host");
            bind(RedisPersistenceProvider.class);
        }
    }

    private void configMilvus() {
        var milvus = MilvusConfig.builder()
                .context(this.context)
                .name("milvus")
                .uri(requiredProperty("sys.milvus.uri"))
                .token(property("sys.milvus.token").orElse(null))
                .database(property("sys.milvus.database").orElse(null))
                .username(property("sys.milvus.username").orElse(null))
                .password(property("sys.milvus.password").orElse(null)).build();
        context.beanFactory.bind(MilvusClientV2.class, "milvus-client", milvus);
        bind(MilvusVectorStore.class);
    }

    private void bindServices() {
        bind(HnswLibVectorStore.class);
        bind(TaskService.class);
    }
}
