package ai.core.llm;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context windows reported by a live model catalog (e.g. the gateway) for models missing from the bundled registry.
 * Resolved once per process: the catalog is authoritative and the lookup can hit the network.
 *
 * @author stephen
 */
final class ExternalModelContextWindows {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalModelContextWindows.class);

    @SuppressFBWarnings(value = "PMB_POSSIBLE_MEMORY_BLOAT",
            justification = "keyed by catalog model names, a finite set resolved once per process")
    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();
    private static volatile ModelContextWindowProvider provider;

    static void setProvider(ModelContextWindowProvider newProvider) {
        provider = newProvider;
    }

    /** @return the max input tokens, or 0 when no provider can resolve the model */
    static int resolve(String modelName) {
        var cached = CACHE.get(modelName);
        if (cached != null) {
            return cached;
        }
        var current = provider;
        if (current == null) {
            return 0;
        }
        try {
            int resolved = current.contextWindow(modelName);
            if (resolved > 0) {
                CACHE.put(modelName, resolved);
                return resolved;
            }
        } catch (RuntimeException e) {
            LOGGER.debug("external context window lookup failed for {}", modelName, e);
        }
        return 0;
    }

    private ExternalModelContextWindows() {
    }
}
