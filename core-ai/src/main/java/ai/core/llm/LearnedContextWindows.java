package ai.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context windows learned from upstream rejections. Process-local: a provider can be stricter than
 * every catalog, and its own rejection is the strongest evidence available at runtime. A learned
 * value only ever moves down, so a transient failure cannot widen the window.
 *
 * @author stephen
 */
public final class LearnedContextWindows {
    private static final Logger LOGGER = LoggerFactory.getLogger(LearnedContextWindows.class);
    private static final int MAX_LEARNED_CONTEXT_WINDOW = 10_000_000;
    private static final Map<String, Integer> WINDOWS = new ConcurrentHashMap<>();

    public static void record(String modelName, int maxInputTokens) {
        if (maxInputTokens <= 0 || maxInputTokens > MAX_LEARNED_CONTEXT_WINDOW || modelName == null || modelName.isBlank()) {
            return;
        }
        WINDOWS.merge(modelName, maxInputTokens, Math::min);
        LOGGER.warn("learned context window from an upstream rejection, model={}, maxInputTokens={}", modelName, maxInputTokens);
    }

    public static int resolve(String modelName) {
        if (modelName == null) return 0;
        var value = WINDOWS.get(modelName);
        return value == null ? 0 : value;
    }

    /** Exposed for tests so one case cannot leak into the next. */
    public static void clear() {
        WINDOWS.clear();
    }

    private LearnedContextWindows() {
    }
}
