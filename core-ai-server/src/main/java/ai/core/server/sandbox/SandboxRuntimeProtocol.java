package ai.core.server.sandbox;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * JSON wire messages exchanged with {@code core-ai-sandbox-runtime}.
 *
 * @author stephen
 */
public final class SandboxRuntimeProtocol {
    private SandboxRuntimeProtocol() {
    }

    public static class BindRequest {
        @Property(name = "server_url")
        public String serverUrl;
        @Property(name = "token")
        public String token;
        @Property(name = "session_id")
        public String sessionId;
        @Property(name = "agent_name")
        public String agentName;
        @Property(name = "expires_at")
        public long expiresAt;
    }

    public static class ExecuteRequest {
        @Property(name = "tool")
        public String tool;
        @Property(name = "arguments")
        public String arguments;
    }

    public static class HealthResponse {
        @Property(name = "status")
        public String status;
        @Property(name = "bound")
        public Boolean bound;
    }

    public static class ExecuteResponse {
        @Property(name = "status")
        public String status;
        @Property(name = "result")
        public String result;
        @Property(name = "task_id")
        public String taskId;
        @Property(name = "duration_ms")
        public Long durationMs;
    }

    public static class McpStartRequest {
        @Property(name = "id")
        public String id;
        @Property(name = "command")
        public String command;
        @Property(name = "args")
        public List<String> args;
        @Property(name = "env")
        public Map<String, String> env;
    }

    public static class McpStopRequest {
        @Property(name = "id")
        public String id;
    }
}
