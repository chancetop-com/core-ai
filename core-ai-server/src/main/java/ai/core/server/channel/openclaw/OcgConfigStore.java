package ai.core.server.channel.openclaw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public abstract class OcgConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgConfigStore.class);
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    protected abstract Optional<OcgConfigView> get(String id);

    protected abstract Optional<OcgConfigView> findByChannelId(String channelId);

    protected abstract List<OcgConfigView> list();

    protected abstract void insert(OcgConfigView config);

    protected abstract void replace(OcgConfigView config);

    protected abstract void delete(String id);

    public void store(OcgConfigView config) {
        try {
            if (get(config.id).isEmpty()) {
                insert(config);
            } else {
                replace(config);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to persist OCG config to db, id={}", config.id, e);
        }
        LOGGER.info("stored OCG config, id={}, channelId={}", config.id, config.channelId);
    }

    public OcgConfigView load(String id) {
        return get(id).orElse(null);
    }

    public OcgConfigView loadByChannelId(String channelId) {
        return findByChannelId(channelId).orElse(null);
    }

    public Map<String, OcgConfigView> all() {
        var result = new LinkedHashMap<String, OcgConfigView>();
        for (var config : list()) {
            result.put(config.id, config);
        }
        return result;
    }

    public List<OcgConfigView> allWithSandbox() {
        return list().stream()
            .filter(config -> config.sandboxId != null && !config.sandboxId.isBlank())
            .toList();
    }

    public void clearSandbox(String id) {
        var config = get(id).orElse(null);
        if (config == null) return;
        config.sandboxId = null;
        config.sandboxIp = null;
        try {
            replace(config);
        } catch (Exception e) {
            LOGGER.warn("failed to clear OCG sandbox in db, id={}", id, e);
        }
    }

    public void remove(String id) {
        try {
            delete(id);
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
