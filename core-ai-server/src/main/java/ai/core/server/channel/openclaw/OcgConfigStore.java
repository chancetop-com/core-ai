package ai.core.server.channel.openclaw;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class OcgConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgConfigStore.class);
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    MongoCollection<OcgConfigView> collection;

    public void store(OcgConfigView config) {
        try {
            var existing = collection.get(config.id).orElse(null);
            if (existing == null) {
                collection.insert(config);
            } else {
                collection.replace(config);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to persist OCG config to db, id={}", config.id, e);
        }
        LOGGER.info("stored OCG config, id={}, channelId={}", config.id, config.channelId);
    }

    public OcgConfigView load(String id) {
        return collection.get(id).orElse(null);
    }

    public OcgConfigView loadByChannelId(String channelId) {
        var configs = collection.find(Filters.eq("channel_id", channelId));
        return configs.isEmpty() ? null : configs.get(0);
    }

    public Map<String, OcgConfigView> all() {
        var configs = collection.find(Filters.empty());
        var result = new LinkedHashMap<String, OcgConfigView>();
        for (var config : configs) {
            result.put(config.id, config);
        }
        return result;
    }

    public List<OcgConfigView> allWithSandbox() {
        var configs = collection.find(Filters.empty());
        return configs.stream()
                .filter(config -> config.sandboxId != null && !config.sandboxId.isBlank())
                .toList();
    }

    public void clearSandbox(String id) {
        var config = collection.get(id).orElse(null);
        if (config == null) return;
        config.sandboxId = null;
        config.sandboxIp = null;
        try {
            collection.replace(config);
        } catch (Exception e) {
            LOGGER.warn("failed to clear OCG sandbox in db, id={}", id, e);
        }
    }

    public void remove(String id) {
        try {
            collection.delete(id);
        } catch (Exception e) {
            LOGGER.warn("failed to delete OCG config from db, id={}", id, e);
        }
    }

    /**
     * The callback/send secret is server-owned: it is minted here and never supplied by a client, so the value
     * injected into the gateway config and the one signing requests can never drift apart.
     */
    String newSecret() {
        var bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Configs created before the secret became server-owned have none yet: mint one and keep it. */
    public String ensureCallbackSecret(OcgConfigView config) {
        if (config.callbackSecret == null || config.callbackSecret.isBlank()) {
            config.callbackSecret = newSecret();
            store(config);
        }
        return config.callbackSecret;
    }
}
