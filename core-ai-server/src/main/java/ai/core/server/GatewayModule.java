package ai.core.server;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.media.MediaProvider;
import ai.core.server.gateway.GatewayChatCompletionsChannelListener;
import ai.core.server.gateway.GatewayChatCompletionsSseEvent;
import ai.core.server.gateway.GatewayLLMProvider;
import ai.core.server.gateway.GatewayMediaProvider;
import ai.core.server.gateway.GatewayModelController;
import ai.core.server.gateway.GatewayModelDiscoveryService;
import ai.core.server.gateway.GatewayModelService;
import ai.core.server.gateway.GatewayProviderController;
import ai.core.server.gateway.GatewayProviderService;
import ai.core.server.gateway.GatewayProxyController;
import ai.core.server.gateway.GatewayProxyService;
import ai.core.server.gateway.GatewayResponsesChannelListener;
import ai.core.server.gateway.GatewayResponsesSseEvent;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewaySecretProtector;
import ai.core.server.sse.SseEndpointRegistry;
import ai.core.telemetry.LLMTracer;
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
        var gatewaySecretKey = property("gateway.secret.key").map(String::trim).filter(key -> !key.isBlank()).orElse(null);
        var gatewayLegacySecret = requiredProperty("sys.mongo.uri");
        var gatewaySecretProtector = bind(gatewaySecretKey == null ? new GatewaySecretProtector(gatewayLegacySecret) : new GatewaySecretProtector(gatewaySecretKey, gatewayLegacySecret));
        var gatewayRoutingEngine = bind(GatewayRoutingEngine.class);
        bind(GatewayModelDiscoveryService.class);
        bind(GatewayModelService.class);
        bind(GatewayProviderService.class);
        bind(GatewayProxyService.class);
        registerGatewayProviderRoutes();
        registerGatewayModelRoutes();
        registerGatewayProxyRoutes();
        var llmProviders = bean(LLMProviders.class);
        var fallbackLLMProvider = llmProviders.getProviderTypes().isEmpty() ? null : llmProviders.getProvider();
        var gatewayLLMConfig = fallbackLLMProvider == null ? new LLMProviderConfig(null, null, null) : new LLMProviderConfig(fallbackLLMProvider.config);
        var gatewayLLMProvider = new GatewayLLMProvider(gatewayLLMConfig, gatewayRoutingEngine, gatewaySecretProtector, fallbackLLMProvider);
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
        bindGatewayMediaProvider(gatewayRoutingEngine, gatewaySecretProtector);
    }

    private void bindGatewayMediaProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector) {
        var mediaProvider = new GatewayMediaProvider(routingEngine, secretProtector);
        bind(MediaProvider.class, mediaProvider);
    }

    private void registerGatewayProviderRoutes() {
        var gatewayProviderController = bind(GatewayProviderController.class);
        http().route(HTTPMethod.GET, "/api/gateway/providers", gatewayProviderController::list);
        http().route(HTTPMethod.POST, "/api/gateway/providers", gatewayProviderController::create);
        http().route(HTTPMethod.PUT, "/api/gateway/providers/:id", gatewayProviderController::update);
        http().route(HTTPMethod.DELETE, "/api/gateway/providers/:id", gatewayProviderController::delete);
        http().route(HTTPMethod.POST, "/api/gateway/providers/:id/test", gatewayProviderController::test);
    }

    private void registerGatewayModelRoutes() {
        var gatewayModelController = bind(GatewayModelController.class);
        http().route(HTTPMethod.GET, "/api/gateway/models", gatewayModelController::list);
        http().route(HTTPMethod.GET, "/api/gateway/models/available", gatewayModelController::listAvailable);
        http().route(HTTPMethod.POST, "/api/gateway/models", gatewayModelController::create);
        http().route(HTTPMethod.PUT, "/api/gateway/models/:id", gatewayModelController::update);
        http().route(HTTPMethod.DELETE, "/api/gateway/models/:id", gatewayModelController::delete);
        http().route(HTTPMethod.POST, "/api/gateway/models/:id/set-default", gatewayModelController::markDefault);
        http().route(HTTPMethod.POST, "/api/gateway/providers/:id/models/discover", gatewayModelController::discover);
        http().route(HTTPMethod.POST, "/api/gateway/providers/:id/models/import", gatewayModelController::importModels);
    }

    private void registerGatewayProxyRoutes() {
        var gatewayProxyController = bind(GatewayProxyController.class);
        http().route(HTTPMethod.GET, "/api/gateway/v1/models", gatewayProxyController::models);
        http().route(HTTPMethod.POST, "/api/gateway/v1/chat/completions", gatewayProxyController::chatCompletions);
        http().route(HTTPMethod.POST, "/api/gateway/v1/responses", gatewayProxyController::responses);
        http().route(HTTPMethod.POST, "/api/gateway/v1/images/generations", gatewayProxyController::imageGenerations);
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
