package ai.core;

import ai.core.image.providers.LiteLLMImageProvider;
import ai.core.llm.LLMProvider;
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
import com.azure.ai.inference.ModelServiceVersion;
import com.azure.ai.openai.OpenAIServiceVersion;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

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
            var provider = new AzureInferenceProvider(config, key, null, false);
            injectTracerIfAvailable(provider);
            bind(AzureInferenceProvider.class, "openai", provider);
            providers.addProvider(LLMProviderType.OPENAI, provider);
        });
    }

    private void configAzureOpenAI(LLMProviders providers, LLMProviderConfig config) {
        property("azure.api.key").ifPresent(key -> property("azure.api.base").ifPresent(base -> {
            AtomicReference<AzureOpenAIProvider> provider = new AtomicReference<>();
            property("azure.api.version").ifPresentOrElse(version -> provider.set(new AzureOpenAIProvider(config, key, base, OpenAIServiceVersion.valueOf(version))), () -> provider.set(new AzureOpenAIProvider(config, key, base)));
            injectTracerIfAvailable(provider.get());
            bind(provider.get());
            providers.addProvider(LLMProviderType.AZURE, provider.get());
        }));
    }

    private void configAzureInference(LLMProviders providers, LLMProviderConfig config) {
        property("azure.ai.api.key").ifPresent(key -> property("azure.ai.api.base").ifPresent(base -> {
            AtomicReference<AzureInferenceProvider> provider = new AtomicReference<>();
            property("azure.ai.api.version").ifPresentOrElse(version -> provider.set(new AzureInferenceProvider(config, key, base, true, ModelServiceVersion.valueOf(version))), () -> provider.set(new AzureInferenceProvider(config, key, base, true)));
            injectTracerIfAvailable(provider.get());
            bind(provider.get());
            providers.addProvider(LLMProviderType.AZURE_INFERENCE, provider.get());
        }));
    }

    private void configDeepSeek(LLMProviders providers, LLMProviderConfig config) {
        property("deepseek.api.key").ifPresent(key -> {
            var provider = new AzureInferenceProvider(config, key, "https://api.deepseek.com/v1", false);
            injectTracerIfAvailable(provider);
            bind(AzureInferenceProvider.class, "deepseek", provider);
            providers.addProvider(LLMProviderType.DEEPSEEK, provider);
        });
    }

    private void configLiteLLM(LLMProviders providers, LLMProviderConfig config) {
        property("litellm.api.base").ifPresent(base -> {
            var provider = new LiteLLMProvider(config, requiredProperty("litellm.api.base"), property("litellm.api.key").orElse(""));
            injectTracerIfAvailable(provider);
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
        property("llm.timeout.seconds").ifPresent(v -> config.setTimeout(Duration.ofSeconds(Long.parseLong(v))));
        property("llm.connect.timeout.seconds").ifPresent(v -> config.setConnectTimeout(Duration.ofSeconds(Long.parseLong(v))));
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

    private void injectTracerIfAvailable(LLMProvider provider) {
        if (tracer != null) {
            provider.setTracer(tracer);
        }
    }
}
