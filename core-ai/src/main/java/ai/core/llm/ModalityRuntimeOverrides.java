package ai.core.llm;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime capability overrides learned from upstream rejections (400 auto-heal).
 * Entries expire after a TTL so a wrong mark never sticks; an admin declaration
 * in the gateway model config remains the authoritative fix.
 *
 * @author Xander
 */
public final class ModalityRuntimeOverrides {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Map<String, Long> UNSUPPORTED_UNTIL = new ConcurrentHashMap<>();

    public static void markUnsupported(String model, InputModality modality) {
        UNSUPPORTED_UNTIL.put(key(model, modality), System.currentTimeMillis() + TTL.toMillis());
    }

    public static boolean isMarkedUnsupported(String model, InputModality modality) {
        var key = key(model, modality);
        var expiry = UNSUPPORTED_UNTIL.get(key);
        if (expiry == null) return false;
        if (expiry < System.currentTimeMillis()) {
            UNSUPPORTED_UNTIL.remove(key);
            return false;
        }
        return true;
    }

    public static void clear() {
        UNSUPPORTED_UNTIL.clear();
    }

    private static String key(String model, InputModality modality) {
        return model + ":" + modality;
    }

    private ModalityRuntimeOverrides() {
    }
}
