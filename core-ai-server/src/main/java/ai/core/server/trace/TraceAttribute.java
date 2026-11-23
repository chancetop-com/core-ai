package ai.core.server.trace;

/**
 * Trace attribute keys shared by the gateway (producer) and the ingest/analytics path (consumer).
 *
 * @author stephen
 */
public final class TraceAttribute {
    // Set by the gateway on the LLM span: the provider that actually served the call. Analytics and
    // pricing prefer it over the model alias when an alias is registered on several providers.
    public static final String GATEWAY_PROVIDER_ID = "gateway.provider.id";

    private TraceAttribute() {
    }
}
