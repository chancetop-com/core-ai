package ai.core.bootstrap;

import ai.core.document.Tokenizer;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderType;
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
import ai.core.prompt.system.SystemPromptConfig;
import ai.core.prompt.system.SystemPromptProvider;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.FlowTracer;
import ai.core.telemetry.GroupTracer;
import ai.core.telemetry.LLMTracer;
import ai.core.telemetry.TelemetryConfig;
import ai.core.telemetry.TracerBundle;
import ai.core.telemetry.TracerRegistry;
import ai.core.utils.JsonUtil;
import ai.core.vectorstore.VectorStoreProvider;
import ai.core.vectorstore.VectorStoreType;
import ai.core.vectorstore.VectorStores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.ServiceLoader;

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
        configureSystemPromptProvider(result);
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

        var providers = ServiceLoader.load(VectorStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        for (var provider : providers) {
            if (provider.enabled(props::property)) {
                vectorStores.addVectorStore(provider.type(), provider.create(props::property));
            }
        }
        failIfConfiguredButMissing(vectorStores, "sys.milvus.uri", VectorStoreType.MILVUS);
        failIfConfiguredButMissing(vectorStores, "sys.hnswlib.path", VectorStoreType.HNSW_LIB);
    }

    private void failIfConfiguredButMissing(VectorStores vectorStores, String property, VectorStoreType type) {
        if (props.property(property).isPresent() && vectorStores.getVectorStore(type) == null) {
            throw new IllegalStateException(property + " is configured but no " + type
                    + " vector store provider found on classpath; add the corresponding core-ai-vectorstore module");
        }
    }

    private void configureTelemetry(BootstrapResult result) {
        var endpoint = props.property("trace.otlp.endpoint").orElse(null);
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
        result.tracerBundle = new TracerBundle(agentTracer, flowTracer, groupTracer);

        TracerRegistry.setAgentTracer(agentTracer);
        TracerRegistry.setFlowTracer(flowTracer);
        TracerRegistry.setGroupTracer(groupTracer);

        if (endpoint != null && !endpoint.isEmpty()) {
            logger.debug("OpenTelemetry tracing enabled - service: {}, version: {}, environment: {}, endpoint: {}",
                    serviceName, serviceVersion, environment, endpoint);
        } else {
            logger.debug("OpenTelemetry tracing enabled in local mode - service: {}, version: {}, environment: {}",
                    serviceName, serviceVersion, environment);
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

    private void configureSystemPromptProvider(BootstrapResult result) {
        props.property("system.prompt.api.base.url").ifPresent(baseUrl -> {
            var configBuilder = SystemPromptConfig.builder().baseUrl(baseUrl);
            props.property("system.prompt.api.key").ifPresent(configBuilder::apiKey);
            props.property("system.prompt.timeout.seconds").ifPresent(timeout ->
                configBuilder.timeoutSeconds(Integer.parseInt(timeout)));
            configBuilder.trustAll(Boolean.parseBoolean(props.property("system.prompt.api.trust.all").orElse("false")));
            var config = configBuilder.build();
            var provider = new SystemPromptProvider(config, true);

            result.systemPromptProvider = provider;
            logger.debug("System prompt provider initialized with base URL: {}", baseUrl);
        });

        if (props.property("system.prompt.api.base.url").isEmpty()) {
            logger.debug("System prompt provider disabled - system.prompt.api.base.url not configured");
        }
    }

    private void configureLLMProviders(BootstrapResult result) {
        result.llmProviders = LLMProviderFactory.create(props);
        result.liteLLMProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.LITELLM);
        result.openRouterProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.OPENROUTER);
        result.deepSeekProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.DEEPSEEK);
        result.azureProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.AZURE);
        result.openAIProvider = (LiteLLMProvider) result.llmProviders.getProvider(LLMProviderType.OPENAI);
        for (var type : result.llmProviders.getProviderTypes()) {
            injectTracerIfAvailable(result.llmProviders.getProvider(type));
        }
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

    private void injectTracerIfAvailable(LLMProvider provider) {
        if (llmTracer != null) {
            provider.setTracer(llmTracer);
        }
    }
}
