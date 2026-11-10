package ai.core.cli.decision;

import ai.core.cli.auth.AuthConfig;
import ai.core.decision.CoreAiGatewayDecisionProvider;
import ai.core.decision.DecisionModelHint;
import ai.core.decision.DecisionProvider;

import java.util.List;

/**
 * Decision capability of the signed-in hub: the provider that calls {@code /api/gateway/v1/decisions}
 * and the catalog that lists the models it can route to. Empty when the CLI is not signed in.
 *
 * @author stephen
 */
public record DecisionSupport(DecisionProvider provider, DecisionModelCatalog catalog) {
    public static final DecisionSupport EMPTY = new DecisionSupport(null, null);
    private static final Object LOCK = new Object();
    private static volatile DecisionSupport cached;
    private static volatile String cachedKey;

    /**
     * Built once per server+key pair: the provider is stateless and the catalog caches its own snapshot,
     * so every session shares them.
     */
    public static DecisionSupport create(AuthConfig auth) {
        if (auth == null || auth.apiKey() == null || auth.apiKey().isBlank() || auth.serverUrl() == null) {
            return EMPTY;
        }
        var serverUrl = auth.serverUrl().replaceAll("/+$", "");
        var key = serverUrl + "|" + auth.apiKey();
        var current = cached;
        if (current != null && key.equals(cachedKey)) return current;
        synchronized (LOCK) {
            if (cached != null && key.equals(cachedKey)) return cached;
            var provider = new CoreAiGatewayDecisionProvider(serverUrl + "/api/gateway/v1", auth.apiKey());
            var catalog = DecisionModelCatalog.create(auth);
            if (catalog != null) {
                catalog.warmUp();
                catalog.ensureLoaded(1_000);
            }
            cached = new DecisionSupport(provider, catalog);
            cachedKey = key;
            return cached;
        }
    }

    public boolean available() {
        return provider != null;
    }

    public List<DecisionModelHint> models() {
        return catalog == null ? List.of() : catalog.models();
    }

    public String defaultModel() {
        return catalog == null ? null : catalog.defaultModel();
    }
}
