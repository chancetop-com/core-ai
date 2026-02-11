package ai.core;

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
import ai.core.vectorstore.VectorStoreType;
import ai.core.vectorstore.VectorStores;
import ai.core.vectorstore.vectorstores.hnswlib.HnswConfig;
import ai.core.vectorstore.vectorstores.hnswlib.HnswLibVectorStore;
import ai.core.vectorstore.vectorstores.milvus.MilvusConfig;
import ai.core.vectorstore.vectorstores.milvus.MilvusVectorStore;
import core.framework.json.JSON;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * @author stephen
 */
public class MultiAgentModule extends Module {
    private final Logger logger = LoggerFactory.getLogger(MultiAgentModule.class);
    private LLMTracer tracer;

    @Override
    protected void initialize() {
        configPersistenceProvider();
        configVectorStore();
        configTelemetry();
        configLangfusePrompts();
        configLLMProvider();
        configMcpClient();
        warmup();
    }

    private void warmup() {
        logger.info("Warming up tokenizer...");
        Tokenizer.warmup();
        logger.info("Tokenizer warmup completed");
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
        var config = setupLLMProperties();
        configLiteLLM(providers, config);
        configDeepSeek(providers, config);
        configAzureOpenAI(providers, config);
        configOpenAI(providers, config);
    }

    private void configOpenAI(LLMProviders providers, LLMProviderConfig config) {
        property("openai.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(config, "openai");
            var provider = new LiteLLMProvider(providerConfig, property("openai.api.base").orElseThrow(), key);
            injectTracerIfAvailable(provider);
            bind(LiteLLMProvider.class, "openai", provider);
            providers.addProvider(LLMProviderType.OPENAI, provider);
        });
    }

    private void configAzureOpenAI(LLMProviders providers, LLMProviderConfig config) {
        property("azure.api.key").ifPresent(key -> property("azure.api.base").ifPresent(base -> {
            var providerConfig = createProviderConfig(config, "azure");
            var provider = new LiteLLMProvider(providerConfig, property("azure.api.base").orElseThrow(), key);
            injectTracerIfAvailable(provider);
            bind(LiteLLMProvider.class, "azure", provider);
            providers.addProvider(LLMProviderType.AZURE, provider);
        }));
    }

    private void configDeepSeek(LLMProviders providers, LLMProviderConfig config) {
        property("deepseek.api.key").ifPresent(key -> {
            var providerConfig = createProviderConfig(config, "deepseek");
            var provider = new LiteLLMProvider(providerConfig, "https://api.deepseek.com/v1", key);
            injectTracerIfAvailable(provider);
            bind(LiteLLMProvider.class, "deepseek", provider);
            providers.addProvider(LLMProviderType.DEEPSEEK, provider);
        });
    }

    private void configLiteLLM(LLMProviders providers, LLMProviderConfig config) {
        property("litellm.api.base").ifPresent(base -> {
            var providerConfig = createProviderConfig(config, "litellm");
            var provider = new LiteLLMProvider(providerConfig, requiredProperty("litellm.api.base"), property("litellm.api.key").orElse(""));
            injectTracerIfAvailable(provider);
            bind(provider);
            bind(LiteLLMImageProvider.class);
            providers.addProvider(LLMProviderType.LITELLM, provider);
        });
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
        property(prefix + ".model").ifPresent(config::setModel);
        property(prefix + ".temperature").ifPresent(v -> config.setTemperature(Double.parseDouble(v)));
        property(prefix + ".embeddings.model").ifPresent(config::setEmbeddingModel);
        property(prefix + ".request.extra_body").ifPresent(config::setRequestExtraBody);
        property(prefix + ".timeout.seconds").ifPresent(v -> config.setTimeout(Long.parseLong(v)));
        property(prefix + ".connect.timeout.seconds").ifPresent(v -> config.setConnectTimeout(Long.parseLong(v)));
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

    private void configTelemetry() {
        property("trace.otlp.endpoint").ifPresent(endpoint -> {
            logger.info("Initializing OpenTelemetry tracing with endpoint: {}", endpoint);

            var serviceName = property("trace.service.name").orElse("core-ai");
            var serviceVersion = property("trace.service.version").orElse("1.0.0");
            var environment = property("trace.environment").orElse("production");

            var telemetryConfigBuilder = TelemetryConfig.builder()
                .serviceName(serviceName)
                .serviceVersion(serviceVersion)
                .environment(environment)
                .otlpEndpoint(endpoint)
                .enabled(true);

            // Add Basic Auth header for Langfuse if both keys are configured
            var publicKey = property("trace.otlp.public.key");
            var secretKey = property("trace.otlp.secret.key");
            if (publicKey.isPresent() && secretKey.isPresent()) {
                var credentials = publicKey.get() + ":" + secretKey.get();
                var encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                telemetryConfigBuilder.addHeader("Authorization", "Basic " + encodedCredentials);
                logger.info("Langfuse Basic Auth configured for OTLP export");
            }

            var telemetryConfig = telemetryConfigBuilder.build();

            // Create and bind all domain-specific tracers
            var openTelemetry = telemetryConfig.getOpenTelemetry();
            var enabled = telemetryConfig.isEnabled();

            tracer = new LLMTracer(openTelemetry, enabled);
            var agentTracer = new AgentTracer(openTelemetry, enabled);
            var flowTracer = new FlowTracer(openTelemetry, enabled);
            var groupTracer = new GroupTracer(openTelemetry, enabled);

            // Bind all tracers for dependency injection
            bind(tracer);
            bind(agentTracer);
            bind(flowTracer);
            bind(groupTracer);
            bind(telemetryConfig);

            // Register tracers globally for auto-injection in builders
            TracerRegistry.setAgentTracer(agentTracer);
            TracerRegistry.setFlowTracer(flowTracer);
            TracerRegistry.setGroupTracer(groupTracer);

            logger.info("OpenTelemetry tracing enabled and registered globally - service: {}, version: {}, environment: {}",
                serviceName, serviceVersion, environment);
        });

        // If trace.otlp.endpoint is not set, tracing is disabled (no-op)
        if (property("trace.otlp.endpoint").isEmpty()) {
            logger.info("OpenTelemetry tracing disabled - trace.otlp.endpoint not configured");
            tracer = null;
            TracerRegistry.clear();
        }
    }

    private void configLangfusePrompts() {
        property("langfuse.prompt.base.url").ifPresent(baseUrl -> {
            logger.info("Initializing Langfuse prompt management with base URL: {}", baseUrl);

            var configBuilder = LangfusePromptConfig.builder()
                .baseUrl(baseUrl);

            // Add credentials if configured
            var publicKey = property("langfuse.prompt.public.key");
            var secretKey = property("langfuse.prompt.secret.key");
            if (publicKey.isPresent() && secretKey.isPresent()) {
                configBuilder.credentials(publicKey.get(), secretKey.get());
                logger.info("Langfuse prompt credentials configured");
            }

            // Optional timeout configuration
            property("langfuse.prompt.timeout.seconds").ifPresent(timeout ->
                configBuilder.timeoutSeconds(Integer.parseInt(timeout))
            );

            var config = configBuilder.build();
            var promptProvider = new LangfusePromptProvider(config, true);

            // Bind for dependency injection
            bind(promptProvider);
            bind(config);

            // Register globally for auto-injection in builders
            LangfusePromptProviderRegistry.setProvider(promptProvider);

            logger.info("Langfuse prompt management enabled and registered globally");
        });

        // If langfuse.prompt.base.url is not set, prompt management is disabled
        if (property("langfuse.prompt.base.url").isEmpty()) {
            logger.info("Langfuse prompt management disabled - langfuse.prompt.base.url not configured");
            LangfusePromptProviderRegistry.clear();
        }
    }

    private void injectTracerIfAvailable(LLMProvider provider) {
        if (tracer != null) {
            provider.setTracer(tracer);
        }
    }

    @SuppressWarnings("unchecked")
    private void configMcpClient() {
        property("mcp.servers.json").ifPresent(json -> {
            try {
                var mcpServersConfig = (Map<String, Object>) JSON.fromJSON(Map.class, json);
                var manager = McpClientManager.fromConfig(mcpServersConfig);
                manager.warmup();
                bind(manager);
                McpClientManagerRegistry.setManager(manager);

                // Register shutdown hook to close MCP connections
                onShutdown(manager::close);

                logger.info("McpClientManager initialized with {} servers: {}",
                        manager.getServerNames().size(),
                        manager.getServerNames());
            } catch (Exception e) {
                logger.error("Failed to parse mcp.servers.json", e);
            }
        });

        if (property("mcp.servers.json").isEmpty()) {
            logger.info("MCP client disabled - mcp.servers.json not configured");
            McpClientManagerRegistry.clear();
        }
    }
}
