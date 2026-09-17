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
 * @param remoteFetchUnreliable the provider accepts reference URLs but cannot be relied on to download
 *                              them: a pre-signed URL of ours is handed out only when this is false, so
 *                              the reference travels as inline data and the adapter uploads it to the
 *                              provider's own file host instead
 * @author Stephen
 */
public record MediaProviderCapabilities(boolean acceptsRemoteUrl, boolean acceptsInlineData, boolean supportsInteractionChaining,
                                        boolean remoteFetchUnreliable) {

    public static MediaProviderCapabilities of(GatewayProviderConfig provider) {
        return forProtocol(MediaProviderAdapterFactory.protocol(provider));
    }

    static MediaProviderCapabilities forProtocol(String protocol) {
        return switch (protocol) {
            // the interactions API rejects http(s) reference URIs ("Only GCS URIs are supported"),
            // so references must be inlined; it does keep its own interaction state
            case "VERTEX_GEMINI_INTERACTIONS" -> new MediaProviderCapabilities(false, true, true, false);
            // KIE's fetcher times out downloading our pre-signed object storage URLs ("The parameter
            // `image` specified in the request are not valid: Timeout while downloading url=https://
            // <account>.blob.core.windows.net/..."), and the task dies minutes later with nothing
            // rendered; its own upload API does work, so references are inlined and uploaded there
            case "KIE" -> new MediaProviderCapabilities(true, true, false, true);
            // reference arrays are URLs; base64 is accepted but costs an extra upload round trip
            case "OPENAI_COMPATIBLE" -> new MediaProviderCapabilities(true, true, false, false);
            // OPENAI_IMAGES uploads multipart files, the Gemini generateContent protocols take
            // inlineData parts: neither can fetch a URL, so an unknown protocol assumes the same
            default -> new MediaProviderCapabilities(false, true, false, false);
        };
    }
}
