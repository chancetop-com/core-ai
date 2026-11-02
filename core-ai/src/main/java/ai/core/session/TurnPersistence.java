package ai.core.session;

import ai.core.agent.Agent;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort session save used by every turn end, cancel, compact/undo and shutdown path. A save
 * failure must never change the turn result; it is reported once per failure streak through a
 * {@code persistence} custom event.
 *
 * @author stephen
 */
public final class TurnPersistence {
    private static final Map<String, Boolean> FAILED_SESSIONS = new ConcurrentHashMap<>();

    public static boolean save(Agent agent, String sessionId, Logger logger, String reason) {
        if (!agent.hasPersistenceProvider()) return true;
        if (!agent.hasUserMessage()) return true;
        try {
            agent.save(sessionId);
            if (sessionId != null && FAILED_SESSIONS.remove(sessionId) != null) {
                emit(agent, "{\"status\":\"ok\"}");
            }
            return true;
        } catch (RuntimeException e) {
            logger.warn("failed to persist session after {}, sessionId={}", reason, sessionId, e);
            if (sessionId != null && FAILED_SESSIONS.putIfAbsent(sessionId, Boolean.TRUE) == null) {
                emit(agent, failureData(reason, e));
            }
            return false;
        }
    }

    public static void clear(String sessionId) {
        if (sessionId != null) FAILED_SESSIONS.remove(sessionId);
    }

    private static String failureData(String reason, RuntimeException failure) {
        var data = new LinkedHashMap<String, String>();
        data.put("status", "failed");
        data.put("reason", reason == null ? "save failed" : reason);
        data.put("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        return JsonUtil.toJson(data);
    }

    private static void emit(Agent agent, String data) {
        var context = agent.getExecutionContext();
        if (context.getCustomVariables() == null) return;
        if (context.getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY) instanceof CustomEventEmitter emitter) {
            emitter.emit("persistence", data, null, null);
        }
    }

    private TurnPersistence() {
    }
}
