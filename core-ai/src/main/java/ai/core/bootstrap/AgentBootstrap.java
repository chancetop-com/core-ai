package ai.core.bootstrap;

import ai.core.document.Tokenizer;
import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.persistence.PersistenceProviderType;
import ai.core.persistence.PersistenceProviders;
import ai.core.persistence.providers.FilePersistenceProvider;
import ai.core.persistence.providers.RedisPersistenceProvider;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import ai.core.prompt.langfuse.LangfusePromptConfig;
import ai.core.prompt.langfuse.LangfusePromptProvider;
import ai.core.prompt.langfuse.LangfusePromptProviderRegistry;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.FlowTracer;
import ai.core.telemetry.GroupTracer;
import ai.core.telemetry.LLMTracer;
import ai.core.telemetry.TelemetryConfig;
import ai.core.telemetry.TracerRegistry;
import ai.core.utils.JsonUtil;
import ai.core.vectorstore.VectorStoreType;
import ai.core.vectorstore.VectorStores;
import ai.core.vectorstore.vectorstores.hnswlib.HnswConfig;
import ai.core.vectorstore.vectorstores.hnswlib.HnswLibVectorStore;
import ai.core.vectorstore.vectorstores.milvus.MilvusConfig;
import ai.core.vectorstore.vectorstores.milvus.MilvusVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentBootstrap {
    private final Logger logger = LoggerFactory.getLogger(AgentBootstrap.class);
    private final PropertySource props;
    private LLMTracer llmTracer;

    public AgentBootstrap(PropertySource props) {
        this.props = props;
    }

    public BootstrapResult initialize() {
        var result = new BootstrapResult();
        configurePersistenceProviders(result);
        configureVectorStores(result);
        configureTelemetry(result);
        configureLangfusePrompts(result);
        configureLLMProviders(result);
        configureMcpClient(result);
        warmup();
        return result;
    }

    private void warmup() {
        logger.debug("Warming up tokenizer...");
        Tokenizer.warmup();
        logger.debug("Tokenizer warmup completed");
    }

    private void configurePersistenceProviders(BootstrapResult result) {
        var providers = new PersistenceProviders();
        result.persistenceProviders = providers;

        props.property("sys.redis.host").ifPresent(host -> {
            var provider = new RedisPersistenceProvider();
            result.redisPersistenceProvider = provider;
            providers.addPersistenceProvider(PersistenceProviderType.REDIS, provider);
        });

        var temporaryProvider = new TemporaryPersistenceProvider();
        result.temporaryPersistenceProvider = temporaryProvider;
        providers.addPersistenceProvider(PersistenceProviderType.TEMPORARY, temporaryProvider);

        props.property("sys.persistence.file.directory").ifPresent(dir -> {
            var provider = new FilePersistenceProvider(dir);
            result.filePersistenceProvider = provider;
            providers.addPersistenceProvider(PersistenceProviderType.FILE, provider);
        });
    }

    private void configureVectorStores(BootstrapResult result) {
        var vectorStores = new VectorStores();
        result.vectorStores = vectorStores;

        props.property("sys.milvus.uri").ifPresent(uri -> {
            var config = MilvusConfig.builder()
                    .uri(uri)
                    .token(props.property("sys.milvus.token").orElse(null))
                    .database(props.property("sys.milvus.database").orElse(null))
                    .username(props.property("sys.milvus.username").orElse(null))
                    .password(props.property("sys.milvus.password").orElse(null)).build();
            var vectorStore = new MilvusVectorStore(config);
            result.milvusVectorStore = vectorStore;
            vectorStores.addVectorStore(VectorStoreType.MILVUS, vectorStore);
        });

        props.property("sys.hnswlib.path").ifPresent(path -> {
            var vectorStore = new HnswLibVectorStore(HnswConfig.of(path));
            result.hnswLibVectorStore = vectorStore;
            vectorStores.addVectorStore(VectorStoreType.HNSW_LIB, vectorStore);
        });
    }

    private void configureTelemetry(BootstrapResult result) {
        props.property("trace.otlp.endpoint").ifPresent(endpoint -> {
            logger.debug("Initializing OpenTelemetry tracing with endpoint: {}", endpoint);

            var serviceName = props.property("trace.service.name").orElse("core-ai");
            var serviceVersion = props.property("trace.service.version").orElse("1.0.0");
            var environment = props.property("trace.environment").orElse("production");

            var telemetryConfigBuilder = TelemetryConfig.builder()
                    .serviceName(serviceName)
                    .serviceVersion(serviceVersion)
                    .environment(environment)
                    .otlpEndpoint(endpoint)
                    .enabled(true);

            var publicKey = props.property("trace.otlp.public.key");
            var secretKey = props.property("trace.otlp.secret.key");
            if (publicKey.isPresent() && secretKey.isPresent()) {
                var credentials = publicKey.get() + ":" + secretKey.get();
                var encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                telemetryConfigBuilder.addHeader("Authorization", "Basic " + encodedCredentials);
                logger.debug("Langfuse Basic Auth configured for OTLP export");
            }

            var telemetryConfig = telemetryConfigBuilder.build();
            result.telemetryConfig = telemetryConfig;

            var openTelemetry = telemetryConfig.getOpenTelemetry();
            var enabled = telemetryConfig.isEnabled();

            llmTracer = new LLMTracer(openTelemetry, enabled);
            var agentTracer = new AgentTracer(openTelemetry, enabled);
            var flowTracer = new FlowTracer(openTelemetry, enabled);
            var groupTracer = new GroupTracer(openTelemetry, enabled);

            result.llmTracer = llmTracer;
            result.agentTracer = agentTracer;
            result.flowTracer = flowTracer;
            result.groupTracer = groupTracer;

            TracerRegistry.setAgentTracer(agentTracer);
            TracerRegistry.setFlowTracer(flowTracer);
            TracerRegistry.setGroupTracer(groupTracer);

            logger.debug("OpenTelemetry tracing enabled and registered globally - service: {}, version: {}, environment: {}",
                    serviceName, serviceVersion, environment);
        });

        if (props.property("trace.otlp.endpoint").isEmpty()) {
            logger.debug("OpenTelemetry tracing disabled - trace.otlp.endpoint not configured");
            llmTracer = null;
            TracerRegistry.clear();
        }
    }

    private void configureLangfusePrompts(BootstrapResult result) {
        props.property("langfuse.prompt.base.url").ifPresent(baseUrl -> {
            logger.debug("Initializing Langfuse prompt management with base URL: {}", baseUrl);

            var configBuilder = LangfusePromptConfig.builder()
                    .baseUrl(baseUrl);

            var publicKey = props.property("langfuse.prompt.public.key");
            var secretKey = props.property("langfuse.prompt.secret.key");
            if (publicKey.isPresent() && secretKey.isPresent()) {
                configBuilder.credentials(publicKey.get(), secretKey.get());
                logger.debug("Langfuse prompt credentials configured");
            }

            props.property("langfuse.prompt.timeout.seconds").ifPresent(timeout ->
                    configBuilder.timeoutSeconds(Integer.parseInt(timeout))
            );

            var config = configBuilder.build();
            var promptProvider = new LangfusePromptProvider(config, true);

            result.langfusePromptConfig = config;
            result.langfusePromptProvider = promptProvider;

            LangfusePromptProviderRegistry.setProvider(promptProvider);

            logger.debug("Langfuse prompt management enabled and registered globally");
        });

        if (props.property("langfuse.prompt.base.url").isEmpty()) {
            logger.debug("Langfuse prompt management disabled - langfuse.prompt.base.url not configured");
            LangfusePromptProviderRegistry.clear();
        }
    }

    private void configureLLMProviders(BootstrapResult result) {
        var providers = new LLMProviders();
        result.llmProviders = providers;
        var config = setupLLMProperties();

        // LiteLLM
        props.property("litellm.api.base").ifPresent(base -> {
            var providerConfig = createProviderConfig(config, "litellm");
            var provider = new LiteLLMProvider(providerConfig, props.requiredProperty("litellm.api.base"), props.property("litellm.api.key").orElse(""));
            injectTracerIfAvailable(provider);
            result.liteLLMProvider = provider;
            result.liteLLMImageProvider = new LiteLLMImageProvider();
            providers.addProvider(LLMProviderType.LITELLM, provider);
        });

        // OpenRouter
        props.property("openrouter.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(config, "openrouter");
            var provider = new LiteLLMProvider(providerConfig, "https://openrouter.ai/api/v1", key);
            injectTracerIfAvailable(provider);
            result.openRouterProvider = provider;
            providers.addProvider(LLMProviderType.OPENROUTER, provider);
        });

        // DeepSeek
        props.property("deepseek.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(config, "deepseek");
            var provider = new LiteLLMProvider(providerConfig, "https://api.deepseek.com/v1", key);
            injectTracerIfAvailable(provider);
            result.deepSeekProvider = provider;
            providers.addProvider(LLMProviderType.DEEPSEEK, provider);
        });

        // Azure OpenAI
        props.property("azure.api.key").ifPresent(key -> props.property("azure.api.base").ifPresent(base -> {
            var providerConfig = createProviderConfig(config, "azure");
            var provider = new LiteLLMProvider(providerConfig, props.requiredProperty("azure.api.base"), key);
            injectTracerIfAvailable(provider);
            result.azureProvider = provider;
            providers.addProvider(LLMProviderType.AZURE, provider);
        }));

        // OpenAI
        props.property("openai.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(config, "openai");
            var provider = new LiteLLMProvider(providerConfig, props.requiredProperty("openai.api.base"), key);
            injectTracerIfAvailable(provider);
            result.openAIProvider = provider;
            providers.addProvider(LLMProviderType.OPENAI, provider);
        });
    }

    @SuppressWarnings("unchecked")
    private void configureMcpClient(BootstrapResult result) {
        props.property("mcp.servers.json").ifPresent(json -> {
            try {
                var mcpServersConfig = (Map<String, Object>) JsonUtil.fromJson(Map.class, json);
                var manager = McpClientManager.fromConfig(mcpServersConfig);
                McpClientManagerRegistry.notifyCreation(manager);
                manager.warmup();
                result.mcpClientManager = manager;
                McpClientManagerRegistry.setManager(manager);

                logger.debug("McpClientManager initialized with {} servers: {}",
                        manager.getServerNames().size(),
                        manager.getServerNames());
            } catch (Exception e) {
                logger.error("Failed to parse mcp.servers.json", e);
            }
        });

        if (props.property("mcp.servers.json").isEmpty()) {
            logger.debug("MCP client disabled - mcp.servers.json not configured");
            McpClientManagerRegistry.clear();
        }
    }

    private LLMProviderConfig setupLLMProperties() {
        var config = new LLMProviderConfig(null, 0.7d, "text-embedding-3-large");
        applyConfigProperties(config, "llm");
        return config;
    }

    private LLMProviderConfig createProviderConfig(LLMProviderConfig baseConfig, String prefix) {
        var config = new LLMProviderConfig(baseConfig);
        applyConfigProperties(config, prefix);
        return config;
    }

    private void applyConfigProperties(LLMProviderConfig config, String prefix) {
        props.property(prefix + ".model").ifPresent(config::setModel);
        props.property(prefix + ".temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        props.property(prefix + ".embeddings.model").ifPresent(config::setEmbeddingModel);
        props.property(prefix + ".request.extra_body").ifPresent(config::setRequestExtraBody);
        props.property(prefix + ".timeout.seconds").ifPresent(v -> config.setTimeout(Long.parseLong(v)));
        props.property(prefix + ".connect.timeout.seconds").ifPresent(v -> config.setConnectTimeout(Long.parseLong(v)));
    }

    private void injectTracerIfAvailable(LLMProvider provider) {
        if (llmTracer != null) {
            provider.setTracer(llmTracer);
        }
    }
}
