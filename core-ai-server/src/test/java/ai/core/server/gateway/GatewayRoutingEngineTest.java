package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.tool.tools.MediaModelHint;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRoutingEngineTest {
    @Test
    void defaultChatModelIdPrefersMarkedDefault() {
        var engine = engine(List.of(model("a", false, 100), model("b", true, 200), model("c", false, 300)));

        assertEquals("b", engine.defaultChatModelId());
    }

    @Test
    void defaultChatModelIdFallsBackToHighestPriority() {
        var engine = engine(List.of(model("a", false, 100), model("b", false, 200)));

        assertEquals("a", engine.defaultChatModelId());
    }

    @Test
    void defaultChatModelIdIgnoresDisabledAndNonChatModels() {
        var disabled = model("disabled", true, 100);
        disabled.enabled = Boolean.FALSE;
        var videoOnly = model("video-only", false, 200);
        videoOnly.endpointTypes = List.of("video.generations");
        var engine = engine(List.of(disabled, videoOnly, model("fallback", false, 300)));

        assertEquals("fallback", engine.defaultChatModelId());
    }

    @Test
    void defaultChatModelIdAcceptsResponsesOnlyModel() {
        var responsesOnly = model("gpt-6.1-sol", false, 100);
        responsesOnly.endpointTypes = List.of("responses");

        assertEquals("gpt-6.1-sol", engine(List.of(responsesOnly)).defaultChatModelId());
    }

    @Test
    void defaultChatModelIdReturnsNullWhenNoChatModel() {
        var videoOnly = model("video-only", false, 100);
        videoOnly.endpointTypes = List.of("video.generations");
        var engine = engine(List.of(videoOnly));

        assertNull(engine.defaultChatModelId());
    }

    @Test
    void mediaModelHintsExcludeDisabledModels() {
        var enabled = model("gemini-3.1-flash-image", false, 100);
        enabled.endpointTypes = List.of("image.generations");
        var disabled = model("gpt-image-2", false, 200);
        disabled.endpointTypes = List.of("image.generations");
        disabled.enabled = Boolean.FALSE;
        var unset = model("seedream-5-pro", false, 300);
        unset.endpointTypes = List.of("image.generations");
        unset.enabled = null;
        var engine = engine(List.of(enabled, disabled, unset));

        var hints = engine.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION);

        assertEquals(List.of("gemini-3.1-flash-image", "seedream-5-pro"),
                hints.stream().map(MediaModelHint::modelId).toList());
    }

    @Test
    void explicitMediaModelHintsExcludeModelsWithoutEndpointTypes() {
        var image = model("gpt-image-2", false, 100);
        image.endpointTypes = List.of("image.generations");
        var legacy = model("legacy-chat", false, 200);
        legacy.endpointTypes = null;
        var engine = engine(List.of(image, legacy));

        assertEquals(List.of("gpt-image-2"),
                engine.explicitMediaModelHints(GatewayEndpointType.IMAGE_GENERATION).stream()
                        .map(MediaModelHint::modelId).toList());
        assertEquals(List.of("gpt-image-2", "legacy-chat"),
                engine.mediaModelHints(GatewayEndpointType.IMAGE_GENERATION).stream()
                        .map(MediaModelHint::modelId).toList());
    }

    @Test
    void decisionModelHintsListOnlyExplicitDecisionModelsAndDedupeFailoverRows() {
        var primary = model("decision-1", false, 100);
        primary.endpointTypes = List.of("decision");
        var backup = model("decision-1", false, 200);
        backup.id = "decision-1-backup";
        backup.endpointTypes = List.of("decision");
        var chat = model("gpt-6.1-sol", false, 150);
        chat.endpointTypes = List.of("chat.completions");
        var engine = engine(List.of(primary, backup, chat));

        var hints = engine.decisionModelHints();

        assertEquals(List.of("decision-1"), hints.stream().map(ai.core.decision.DecisionModelHint::modelId).toList());
        assertEquals("decision-1", hints.getFirst().upstreamModel());
    }

    @Test
    void defaultDecisionModelIsUsedWhenNoDecisionModelIsRegistered() {
        var provider = provider(null);
        provider.defaultDecisionModel = "Decision-1";
        var engine = new GatewayRoutingEngine();
        engine.providerStore = GatewayStoreTestSupport.providerStore(List.of(provider));
        engine.modelStore = GatewayStoreTestSupport.modelStore(List.of());

        var route = engine.route(null, GatewayEndpointType.DECISION);

        assertEquals("Decision-1", route.upstreamModel());
    }

    @Test
    void routeRejectsDisabledModelInsteadOfFallingBackToTheProviderPrefix() {
        var disabled = model("gemini-3.1-flash-image", false, 100);
        disabled.endpointTypes = List.of("image.generations");
        disabled.enabled = Boolean.FALSE;
        var engine = engine(List.of(disabled), "gemini-");

        var error = assertThrows(BadRequestException.class,
                () -> engine.route("gemini-3.1-flash-image", GatewayEndpointType.IMAGE_GENERATION));

        assertEquals("gateway model is disabled: gemini-3.1-flash-image", error.getMessage());
        assertTrue(engine.isDisabled("gemini-3.1-flash-image"));
        assertFalse(engine.isDisabled("never-registered"));
    }

    @Test
    void routeResolvesPrefixedProviderModelFormat() {
        var engine = engine(List.of(model("deepseek-v4-flash", false, 100)), "deepseek/");

        var route = engine.route("deepseek/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS);

        assertEquals("deepseek-v4-flash", route.upstreamModel());
        assertEquals("provider-1", route.provider().name);
    }

    @Test
    void routePrefersExactModelIdOverPrefixedFormat() {
        var exact = model("deepseek/deepseek-v4-flash", false, 100);
        exact.upstreamModel = "exact-upstream";
        var engine = engine(List.of(exact, model("deepseek-v4-flash", false, 200)), "deepseek/");

        var route = engine.route("deepseek/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS);

        assertEquals("exact-upstream", route.upstreamModel());
    }

    @Test
    void routePrefixedFormatWithWrongProviderPrefixIsRejected() {
        var engine = engine(List.of(model("deepseek-v4-flash", false, 100)), "deepseek/");

        assertThrows(BadRequestException.class,
            () -> engine.route("azure/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS));
    }

    @Test
    void routePrefixedFormatRespectsEndpointSupport() {
        var videoOnly = model("deepseek-v4-flash", false, 100);
        videoOnly.endpointTypes = List.of("video.generations");
        var engine = engine(List.of(videoOnly), "deepseek/");

        assertThrows(BadRequestException.class,
            () -> engine.route("deepseek/deepseek-v4-flash", GatewayEndpointType.CHAT_COMPLETIONS));
    }

    @Test
    void messagesEndpointRoutesBothChatAndMessagesModels() {
        var chat = model("deepseek-chat", false, 100);
        var messages = model("claude-sonnet", false, 200);
        messages.endpointTypes = List.of("messages");
        var engine = engine(List.of(chat, messages));

        assertEquals("deepseek-chat", engine.route("deepseek-chat", GatewayEndpointType.MESSAGES).upstreamModel());
        assertEquals("claude-sonnet", engine.route("claude-sonnet", GatewayEndpointType.MESSAGES).upstreamModel());
    }

    @Test
    void messagesFallbackRoutesUnregisteredModels() {
        var fallback = model("deepseek-flash", false, 100);
        fallback.messagesFallback = Boolean.TRUE;
        var engine = engine(List.of(fallback, model("gpt-6", false, 200)));

        var route = engine.route("claude-sonnet-4-6", GatewayEndpointType.MESSAGES);

        assertEquals("deepseek-flash", route.upstreamModel());
        assertEquals("deepseek-flash", route.model().modelId);
    }

    @Test
    void messagesFallbackDoesNotApplyToOtherEndpoints() {
        var fallback = model("deepseek-flash", false, 100);
        fallback.messagesFallback = Boolean.TRUE;
        var engine = engine(List.of(fallback));

        assertThrows(BadRequestException.class, () -> engine.route("claude-sonnet-4-6", GatewayEndpointType.CHAT_COMPLETIONS));
    }

    @Test
    void messagesFallbackDoesNotServeDisabledModels() {
        var fallback = model("deepseek-flash", false, 100);
        fallback.messagesFallback = Boolean.TRUE;
        var disabled = model("claude-sonnet-4-6", false, 200);
        disabled.enabled = Boolean.FALSE;
        var engine = engine(List.of(fallback, disabled));

        assertThrows(BadRequestException.class, () -> engine.route("claude-sonnet-4-6", GatewayEndpointType.MESSAGES));
    }

    @Test
    void messagesEndpointRejectsResponsesOnlyModels() {
        var responsesOnly = model("gpt-6.1-sol", false, 100);
        responsesOnly.endpointTypes = List.of("responses");
        var engine = engine(List.of(responsesOnly));

        assertThrows(BadRequestException.class, () -> engine.route("gpt-6.1-sol", GatewayEndpointType.MESSAGES));
    }

    @Test
    void fallbackModelsPreserveConfiguredOrderAndDropDuplicates() {
        var primary = model("deepseek", false, 100);
        primary.fallbackModelIds = List.of("gpt-6", "deepseek", "gemini", "gpt-6", "missing");
        var engine = engine(List.of(primary, model("gpt-6", false, 200), model("gemini", false, 300)));

        assertEquals(List.of("gpt-6", "gemini"),
                engine.fallbackModels("deepseek").stream().map(model -> model.modelId).toList());
    }

    @Test
    void fallbackModelsReturnEmptyWhenNotConfigured() {
        var engine = engine(List.of(model("deepseek", false, 100)));

        assertTrue(engine.fallbackModels("deepseek").isEmpty());
        assertTrue(engine.fallbackModels("unknown").isEmpty());
        assertTrue(engine.fallbackModels(null).isEmpty());
    }

    @Test
    void fallbackModelsKeepDisabledModelsForCallerToSkip() {
        var primary = model("deepseek", false, 100);
        primary.fallbackModelIds = List.of("gpt-6");
        var disabled = model("gpt-6", false, 200);
        disabled.enabled = Boolean.FALSE;
        var engine = engine(List.of(primary, disabled));

        assertEquals(List.of("gpt-6"),
                engine.fallbackModels("deepseek").stream().map(model -> model.modelId).toList());
    }

    @Test
    void modelConfigAcceptsPrefixedProviderModelFormat() {
        var engine = engine(List.of(model("deepseek-v4-flash", false, 100)), "deepseek/");

        assertEquals("deepseek-v4-flash", engine.modelConfig("deepseek/deepseek-v4-flash").modelId);
        assertNull(engine.modelConfig("deepseek/other-model"));
        assertNull(engine.modelConfig("azure/deepseek-v4-flash"));
    }

    @SuppressWarnings("unchecked")
    private GatewayRoutingEngine engine(List<GatewayModelConfig> models) {
        return engine(models, null);
    }

    @SuppressWarnings("unchecked")
    private GatewayRoutingEngine engine(List<GatewayModelConfig> models, String modelPrefix) {
        var engine = new GatewayRoutingEngine();
        engine.providerStore = GatewayStoreTestSupport.providerStore(List.of(provider(modelPrefix)));
        engine.modelStore = GatewayStoreTestSupport.modelStore(models);
        return engine;
    }

    private GatewayProviderConfig provider(String modelPrefix) {
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.name = "provider-1";
        provider.type = "litellm";
        provider.modelPrefix = modelPrefix;
        provider.enabled = Boolean.TRUE;
        return provider;
    }

    private GatewayModelConfig model(String modelId, boolean isDefault, long priority) {
        var model = new GatewayModelConfig();
        model.id = modelId;
        model.modelId = modelId;
        model.providerId = "provider-1";
        model.upstreamModel = modelId;
        model.endpointTypes = List.of("chat.completions");
        model.enabled = Boolean.TRUE;
        model.isDefault = isDefault;
        model.priority = priority;
        return model;
    }
}
