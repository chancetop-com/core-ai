package ai.core;

import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.AzureInferenceProvider;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.persistence.providers.RedisPersistenceProvider;
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
            requiredProperty("sys.milvus.uri");
            configMilvus();
        }
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
        var config = new LLMProviderConfig("gpt-4o", 0.7d, "text-embedding-ada-002");
        property("llm.temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        property("llm.model").ifPresent(config::setModel);
        property("llm.embedding.model").ifPresent(config::setEmbeddingModel);
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
        bind(TaskService.class);
    }
}
