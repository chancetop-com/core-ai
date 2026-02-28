package ai.core;

import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.llm.providers.LiteLLMProvider;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class MultiAgentModule extends Module {
    @Override
    protected void initialize() {
        var bootstrap = new AgentBootstrap(this::property);
        var result = bootstrap.initialize();
        bindResult(result);
    }

    private void bindResult(BootstrapResult r) {
        bind(r.llmProviders);
        bind(r.persistenceProviders);
        bind(r.vectorStores);
        bindLLMProviders(r);
        bindPersistenceProviders(r);
        bindVectorStores(r);
        bindTelemetry(r);
        bindLangfuse(r);
        bindMcp(r);
    }

    private void bindLLMProviders(BootstrapResult r) {
        if (r.liteLLMProvider != null) {
            bind(r.liteLLMProvider);
        }
        if (r.liteLLMImageProvider != null) {
            bind(r.liteLLMImageProvider);
        }
        if (r.openAIProvider != null) {
            bind(LiteLLMProvider.class, "openai", r.openAIProvider);
        }
        if (r.azureProvider != null) {
            bind(LiteLLMProvider.class, "azure", r.azureProvider);
        }
        if (r.deepSeekProvider != null) {
            bind(LiteLLMProvider.class, "deepseek", r.deepSeekProvider);
        }
    }

    private void bindPersistenceProviders(BootstrapResult r) {
        if (r.temporaryPersistenceProvider != null) {
            bind(r.temporaryPersistenceProvider);
        }
        if (r.redisPersistenceProvider != null) {
            bind(r.redisPersistenceProvider);
        }
        if (r.filePersistenceProvider != null) {
            bind(r.filePersistenceProvider);
        }
    }

    private void bindVectorStores(BootstrapResult r) {
        if (r.milvusVectorStore != null) {
            bind(r.milvusVectorStore);
        }
        if (r.hnswLibVectorStore != null) {
            bind(r.hnswLibVectorStore);
        }
    }

    private void bindTelemetry(BootstrapResult r) {
        if (r.telemetryConfig != null) {
            bind(r.telemetryConfig);
        }
        if (r.llmTracer != null) {
            bind(r.llmTracer);
        }
        if (r.agentTracer != null) {
            bind(r.agentTracer);
        }
        if (r.flowTracer != null) {
            bind(r.flowTracer);
        }
        if (r.groupTracer != null) {
            bind(r.groupTracer);
        }
    }

    private void bindLangfuse(BootstrapResult r) {
        if (r.langfusePromptConfig != null) {
            bind(r.langfusePromptConfig);
        }
        if (r.langfusePromptProvider != null) {
            bind(r.langfusePromptProvider);
        }
    }

    private void bindMcp(BootstrapResult r) {
        if (r.mcpClientManager != null) {
            bind(r.mcpClientManager);
            onShutdown(r.mcpClientManager::close);
        }
    }
}
