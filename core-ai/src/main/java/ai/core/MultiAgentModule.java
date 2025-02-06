package ai.core;

import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.rag.vectorstore.milvus.MilvusConfig;
import ai.core.rag.vectorstore.milvus.MilvusVectorStore;
import ai.core.task.TaskService;
import core.framework.module.Module;
import io.milvus.v2.client.MilvusClientV2;

import javax.annotation.Nullable;

/**
 * @author stephen
 */
public class MultiAgentModule extends Module {
    @Override
    protected void initialize() {
        this.property("sys.milvus.uri").ifPresent(v -> configMilvus(null));
        load(new LiteLLMModule());
        bindServices();
    }

    private void configMilvus(@Nullable String name) {
        var milvus = MilvusConfig.builder()
                .context(this.context)
                .name(name)
                .uri(requiredProperty("sys.milvus.uri"))
                .token(property("sys.milvus.token").orElse(null))
                .database(property("sys.milvus.database").orElse(null))
                .username(property("sys.milvus.username").orElse(null))
                .password(property("sys.milvus.password").orElse(null)).build();
        context.beanFactory.bind(MilvusClientV2.class, name, milvus);
    }

    private void bindServices() {
        bind(new LiteLLMProvider(setupLLMProperties()));
        bind(LiteLLMImageProvider.class);
        bind(TaskService.class);
        bind(MilvusVectorStore.class);
    }

    private LLMProviderConfig setupLLMProperties() {
        var config = new LLMProviderConfig("gpt-4o", 0.7d);
        property("llm.temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        property("llm.model").ifPresent(config::setModel);
        return config;
    }
}
