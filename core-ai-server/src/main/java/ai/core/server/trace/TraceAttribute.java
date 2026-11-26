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

    // Set on the LLM span by whoever knows the real serving model: the gateway on the server path
    // (gateway model id of the attempt that succeeded after failover) or the client engine on the
    // CLI/desktop path (the model the upstream reported in its response). It differs from the
    // requested alias only when an attempt failed over or an alias maps to a renamed upstream.
    // Pricing and the trace UI prefer it over the alias.
    public static final String GEN_AI_RESOLVED_MODEL = "gen_ai.resolved_model";

    private TraceAttribute() {
    }
}
