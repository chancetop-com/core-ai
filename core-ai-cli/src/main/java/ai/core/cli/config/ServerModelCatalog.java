package ai.core.cli.config;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.cli.hub.model.ModelHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Chat models the logged-in user may use, from the hub ({@code /api/hub/models}) — the same source the
 * desktop model picker reads through the app-server. Null when not logged in or unreachable; the caller
 * then falls back to the local registry. Cached briefly so an offline server cannot stall every /model.
 *
 * @author stephen
 */
public final class ServerModelCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerModelCatalog.class);
    private static final long TTL_MS = 60_000L;
    private static final ServerModelCatalog INSTANCE = new ServerModelCatalog(ServerModelCatalog::fetch);

    public static ServerModelCatalog instance() {
        return INSTANCE;
    }

    private static List<GatewayAvailableModelView> fetch() {
        var auth = AuthConfig.load();
        if (auth == null || auth.serverUrl() == null || auth.apiKey() == null) {
            return null;
        }
        return new ModelHubClient(auth.serverUrl(), auth.apiKey(), auth.insecure()).models().models;
    }

    private final Supplier<List<GatewayAvailableModelView>> fetcher;
    private volatile List<GatewayAvailableModelView> cached;
    private volatile long fetchedAt;

    ServerModelCatalog(Supplier<List<GatewayAvailableModelView>> fetcher) {
        this.fetcher = fetcher;
        RuntimeAuthConfig.instance().addListener(this::invalidate);
    }

    public List<GatewayAvailableModelView> models() {
        var now = System.currentTimeMillis();
        if (fetchedAt != 0 && now - fetchedAt < TTL_MS) {
            return cached;
        }
        List<GatewayAvailableModelView> fetched = null;
        try {
            fetched = fetcher.get();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to fetch hub models: {}", e.getMessage());
        }
        cached = fetched;
        fetchedAt = now;
        return fetched;
    }

    public GatewayAvailableModelView find(String modelId) {
        var models = models();
        if (models == null || modelId == null) {
            return null;
        }
        return models.stream()
                .filter(model -> modelId.equals(model.modelId))
                .findFirst()
                .orElse(null);
    }

    public void invalidate() {
        cached = null;
        fetchedAt = 0;
    }
}
