package ai.core.server.tool;

import ai.core.server.domain.OutboundCallerHeaderConfig;
import ai.core.server.domain.User;
import ai.core.tool.CallerHeaderProvider;
import ai.core.tool.OutboundCallerContext.Caller;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves outbound caller headers from the manager user's {@code outbound_caller_headers}
 * config. Config is cached per manager with a short TTL so changes take effect quickly
 * without a per-request Mongo read on every tool call.
 *
 * @author stephen
 */
public class CallerHeaderConfigResolver implements CallerHeaderProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallerHeaderConfigResolver.class);
    private static final long CACHE_TTL_MILLIS = 5_000;

    @Inject
    MongoCollection<User> userCollection;

    private final Map<String, CachedConfig> cache = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> headersFor(Caller caller) {
        if (caller == null || caller.managerId() == null) return Map.of();
        var configs = resolveConfigs(caller.managerId());
        if (configs == null || configs.isEmpty()) return Map.of();
        var headers = new HashMap<String, String>();
        for (var config : configs) {
            var value = resolveValue(caller, config.valueSource);
            if (value != null) {
                headers.put(config.headerName, value);
            }
        }
        return headers;
    }

    private List<OutboundCallerHeaderConfig> resolveConfigs(String managerId) {
        var now = System.currentTimeMillis();
        var cached = cache.get(managerId);
        if (cached != null && now - cached.resolvedAt < CACHE_TTL_MILLIS) {
            return cached.configs;
        }
        var manager = userCollection.get(managerId).orElse(null);
        var configs = manager == null ? null : manager.outboundCallerHeaders;
        if (manager == null) {
            LOGGER.warn("manager user not found for caller header config, managerId={}", managerId);
        }
        cache.put(managerId, new CachedConfig(configs, now));
        return configs;
    }

    private String resolveValue(Caller caller, String source) {
        if ("external_id".equals(source)) return caller.externalId();
        if ("user_id".equals(source)) return caller.userId();
        if ("manager_id".equals(source)) return caller.managerId();
        if (source != null && source.startsWith("metadata.") && caller.metadata() != null) {
            return caller.metadata().get(source.substring("metadata.".length()));
        }
        return null;
    }

    private record CachedConfig(List<OutboundCallerHeaderConfig> configs, long resolvedAt) {
    }
}
