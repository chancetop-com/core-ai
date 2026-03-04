package ai.core.bootstrap;

import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.mcp.client.McpClientManager;
import ai.core.persistence.PersistenceProviders;
import ai.core.persistence.providers.FilePersistenceProvider;
import ai.core.persistence.providers.RedisPersistenceProvider;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import ai.core.prompt.langfuse.LangfusePromptConfig;
import ai.core.prompt.langfuse.LangfusePromptProvider;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.FlowTracer;
import ai.core.telemetry.GroupTracer;
import ai.core.telemetry.LLMTracer;
import ai.core.telemetry.TelemetryConfig;
import ai.core.vectorstore.VectorStores;
import ai.core.vectorstore.vectorstores.hnswlib.HnswLibVectorStore;
import ai.core.vectorstore.vectorstores.milvus.MilvusVectorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class BootstrapResult {
    public LLMProviders llmProviders;
    public PersistenceProviders persistenceProviders;
    public VectorStores vectorStores;

    public LiteLLMProvider liteLLMProvider;
    public LiteLLMImageProvider liteLLMImageProvider;
    public LiteLLMProvider openAIProvider;
    public LiteLLMProvider azureProvider;
    public LiteLLMProvider deepSeekProvider;
    public LiteLLMProvider openRouterProvider;

    public TemporaryPersistenceProvider temporaryPersistenceProvider;
    public RedisPersistenceProvider redisPersistenceProvider;
    public FilePersistenceProvider filePersistenceProvider;

    public MilvusVectorStore milvusVectorStore;
    public HnswLibVectorStore hnswLibVectorStore;

    public TelemetryConfig telemetryConfig;
    public LLMTracer llmTracer;
    public AgentTracer agentTracer;
    public FlowTracer flowTracer;
    public GroupTracer groupTracer;

    public LangfusePromptConfig langfusePromptConfig;
    public LangfusePromptProvider langfusePromptProvider;

    public McpClientManager mcpClientManager;

    public List<AutoCloseable> shutdownResources() {
        var list = new ArrayList<AutoCloseable>();
        if (mcpClientManager != null) list.add(mcpClientManager);
        return list;
    }
}
