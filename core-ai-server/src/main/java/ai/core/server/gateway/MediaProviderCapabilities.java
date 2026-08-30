package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;

/**
 * What a destination provider can actually consume as a reference. Declared once next to the adapter
 * factory and keyed by the same {@code mediaProtocol} switch {@link MediaProviderAdapterFactory}
 * already has, so no provider-specific logic leaks into the tools.
 *
 * @param acceptsRemoteUrl the provider fetches reference URLs from the public internet
 * @param acceptsInlineData the provider accepts inline base64 reference data
 * @param supportsInteractionChaining the provider keeps its own conversation state, so a prior
 *                                    generation can be continued without re-sending any asset
 * @author Stephen
 */
public record MediaProviderCapabilities(boolean acceptsRemoteUrl, boolean acceptsInlineData, boolean supportsInteractionChaining) {

    public static MediaProviderCapabilities of(GatewayProviderConfig provider) {
        return forProtocol(MediaProviderAdapterFactory.protocol(provider));
    }

    static MediaProviderCapabilities forProtocol(String protocol) {
        return switch (protocol) {
            // takes a gs:// or https:// uri or inline data, and keeps its own interaction state
            case "VERTEX_GEMINI_INTERACTIONS" -> new MediaProviderCapabilities(true, true, true);
            // reference arrays are URLs; base64 is accepted but costs an extra upload round trip
            case "KIE", "OPENAI_COMPATIBLE" -> new MediaProviderCapabilities(true, true, false);
            // OPENAI_IMAGES uploads multipart files, the Gemini generateContent protocols take
            // inlineData parts: neither can fetch a URL, so an unknown protocol assumes the same
            default -> new MediaProviderCapabilities(false, true, false);
        };
    }
}
