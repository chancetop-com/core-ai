package ai.core.server;

import ai.core.api.server.gateway.GatewayModelWebService;
import ai.core.api.server.gateway.GatewayProviderWebService;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.media.MediaProvider;
import ai.core.server.gateway.GatewayChatCompletionsChannelListener;
import ai.core.server.gateway.GatewayChatCompletionsSseEvent;
import ai.core.server.gateway.GatewayLLMProvider;
import ai.core.server.gateway.GatewayMediaProvider;
import ai.core.server.gateway.GatewayModalityRegistry;
import ai.core.server.gateway.GatewayModelDiscoveryService;
import ai.core.server.gateway.GatewayModelService;
import ai.core.server.gateway.GatewayModelWebServiceImpl;
import ai.core.server.gateway.GatewayProviderService;
import ai.core.server.gateway.GatewayProviderWebServiceImpl;
import ai.core.server.gateway.GatewayProxyController;
import ai.core.server.gateway.GatewayProxyService;
import ai.core.server.gateway.GatewayResponsesChannelListener;
import ai.core.server.gateway.GatewayResponsesSseEvent;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.MediaJobService;
import ai.core.server.domain.GeminiFileRepository;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.domain.GeminiFileService;
import ai.core.server.domain.GeminiFilesClient;
import ai.core.server.domain.GeminiVideoUnderstandingService;
import ai.core.server.gateway.GatewaySecretProtector;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.telemetry.LLMTracer;
import ai.core.tool.tools.UnderstandVideoTool;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class GatewayModule extends Module {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayModule.class);

    @Override
    protected void initialize() {
        configureGateway();
    }

    private void configureGateway() {
        var gatewaySecretProtector = bean(GatewaySecretProtector.class);
        var gatewayRoutingEngine = bind(GatewayRoutingEngine.class);
        bind(GeminiFileRepository.class);
        bind(SessionAttachmentRefRepository.class);
        bind(GeminiFileService.class);
        var videoUnderstandingService = bind(GeminiVideoUnderstandingService.class);
        bind(UnderstandVideoTool.VideoUnderstandingService.class, videoUnderstandingService);
        var geminiApiKey = property("gemini.api.key").orElse(null);
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            bean(GeminiFileService.class).configure(new GeminiFilesClient(null, geminiApiKey));
        }
        bind(GatewayModelDiscoveryService.class);
        bind(GatewayModelService.class);
        bind(GatewayProviderService.class);
        var mediaJobService = bind(MediaJobService.class);
        bind(GatewayProxyService.class);
        registerGatewayProviderRoutes();
        registerGatewayModelRoutes();
        registerGatewayProxyRoutes();
        var llmProviders = bean(LLMProviders.class);
        var fallbackLLMProvider = llmProviders.getProviderTypes().isEmpty() ? null : llmProviders.getProvider();
        var gatewayLLMConfig = fallbackLLMProvider == null ? new LLMProviderConfig(null, null, null) : new LLMProviderConfig(fallbackLLMProvider.config);
        var gatewayLLMProvider = new GatewayLLMProvider(gatewayLLMConfig, gatewayRoutingEngine, gatewaySecretProtector, fallbackLLMProvider);
        gatewayLLMProvider.setModalityRegistry(new GatewayModalityRegistry(gatewayRoutingEngine));
        if (fallbackLLMProvider != null) {
            gatewayLLMProvider.setTracer(fallbackLLMProvider.getTracer());
        } else {
            try {
                gatewayLLMProvider.setTracer(bean(LLMTracer.class));
            } catch (Error e) {
                LOGGER.info("no LLMTracer configured, gateway LLM calls run untraced");
            }
        }
        bind(gatewayLLMProvider);
        llmProviders.addProvider(LLMProviderType.GATEWAY, gatewayLLMProvider);
        llmProviders.setDefaultProvider(LLMProviderType.GATEWAY);
        bindGatewayMediaProvider(gatewayRoutingEngine, gatewaySecretProtector, mediaJobService);
    }

    private void bindGatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector, MediaJobService mediaJobService) {
        var mediaProvider = new GatewayMediaProvider(routingEngine, secretProtector, mediaJobService);
        bind(MediaProvider.class, mediaProvider);
    }

    private void registerGatewayProviderRoutes() {
        api().service(GatewayProviderWebService.class, bind(GatewayProviderWebServiceImpl.class));
    }

    private void registerGatewayModelRoutes() {
        api().service(GatewayModelWebService.class, bind(GatewayModelWebServiceImpl.class));
    }

    private void registerGatewayProxyRoutes() {
        var gatewayProxyController = bind(GatewayProxyController.class);
        http().route(HTTPMethod.GET, "/api/gateway/v1/models", gatewayProxyController::models);
        http().route(HTTPMethod.POST, "/api/gateway/v1/chat/completions", gatewayProxyController::chatCompletions);
        http().route(HTTPMethod.POST, "/api/gateway/v1/responses", gatewayProxyController::responses);
        http().route(HTTPMethod.POST, "/api/gateway/v1/images/generations", gatewayProxyController::imageGenerations);
        http().route(HTTPMethod.POST, "/api/gateway/v1/images/edits", gatewayProxyController::imageEdits);
        http().route(HTTPMethod.POST, "/api/gateway/v1/videos", gatewayProxyController::videoGenerations);
        http().route(HTTPMethod.GET, "/api/gateway/v1/videos/:id", gatewayProxyController::videoStatus);
        http().route(HTTPMethod.GET, "/api/gateway/v1/videos/:id/content", gatewayProxyController::videoContent);
        var registry = bean(SseEndpointRegistry.class);
        registry.register(HTTPMethod.POST, "/api/gateway/v1/chat/completions", GatewayChatCompletionsSseEvent.class,
                bind(GatewayChatCompletionsChannelListener.class), true);
        registry.register(HTTPMethod.POST, "/api/gateway/v1/responses", GatewayResponsesSseEvent.class,
                bind(GatewayResponsesChannelListener.class), true);
    }
}
