package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.session.lifecycle.TaskRunSnapshot;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * @author stephen
 */
final class SessionToolSupport {
    static Integer intValue(Map<String, Object> args, String key) {
        var value = args.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    static String errorText(RuntimeException e) {
        if (e instanceof RpcException rpc && rpc.data() != null) {
            var code = rpc.data().path("code").asText(null);
            if (code != null) return code + ": " + rpc.getMessage();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static EngineSession requireSession(EngineSessionRegistry registry, String sessionId) {
        var live = registry.find(sessionId);
        if (live != null) return live;
        var resumed = registry.resumeQuietly(sessionId);
        if (resumed == null) throw RpcException.business("SESSION_NOT_FOUND", "session not found: " + sessionId);
        return resumed;
    }

    static String text(JsonNode node) {
        return node == null ? "" : node.toPrettyString();
    }

    static HostRequestBridge.RequestContext hostContext(ExecutionContext context, EngineSessionRegistry registry) {
        var session = registry.find(context.getSessionId());
        TaskRunSnapshot active = session == null ? null : session.inProcess().taskRuns().activeSnapshot();
        return new HostRequestBridge.RequestContext(context.getSessionId(),
                active == null ? null : active.taskRunId(),
                active == null ? null : active.activeExecutionId());
    }

    static RunRef parseRunRef(String value) {
        if (value == null || value.isBlank()) return null;
        var text = value.strip();
        int hash = text.lastIndexOf('#');
        if (hash > 0 && hash < text.length() - 1) {
            try {
                return new RunRef(text.substring(0, hash), Long.parseLong(text.substring(hash + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new RunRef(text, 0);
    }

    private SessionToolSupport() {
    }

    record RunRef(String taskRunId, long runGeneration) {
    }
}
