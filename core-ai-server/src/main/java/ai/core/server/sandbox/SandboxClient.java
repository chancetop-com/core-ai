package ai.core.server.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.SandboxConstants;
import ai.core.tool.ToolCallResult;
import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * @author stephen
 */
public class SandboxClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxClient.class);

    private final String baseUrl;
    private final String ip;
    private final int port;
    private final HTTPClient httpClient;

    public SandboxClient(String ip, int port, int timeoutSeconds) {
        this.ip = ip;
        this.port = port;
        this.baseUrl = "http://" + ip + ":" + port;
        var timeoutMs = timeoutSeconds > 0 ? timeoutSeconds * 1000L : SandboxConstants.DEFAULT_TOOL_TIMEOUT_MS;
        this.httpClient = HTTPClient.builder().timeout(Duration.ofMillis(timeoutMs)).build();
    }

    public void waitForReady(int maxWaitMs) {
        var url = baseUrl + "/health";
        var start = System.currentTimeMillis();
        var pollClient = HTTPClient.builder()
                .connectTimeout(Duration.ofSeconds(2))
                .timeout(Duration.ofSeconds(3))
                .build();
        while (System.currentTimeMillis() - start < maxWaitMs) {
            try {
                var req = new HTTPRequest(HTTPMethod.GET, url);
                var resp = pollClient.execute(req);
                if (resp.statusCode == 200) {
                    LOGGER.info("sandbox runtime ready: url={}, elapsed={}ms", baseUrl, System.currentTimeMillis() - start);
                    return;
                }
            } catch (Exception e) {
                LOGGER.warn("sandbox runtime health check failed: url={}, elapsed={}ms, error={}", baseUrl, System.currentTimeMillis() - start, e.getMessage());
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOGGER.warn("sandbox runtime health check timed out after {}ms: url={}", maxWaitMs, baseUrl);
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        try {
            var request = new ExecuteRequest();
            request.tool = toolName;
            request.arguments = arguments;
            var requestJson = JSON.toJSON(request);

            var url = baseUrl + "/execute";
            var req = new HTTPRequest(HTTPMethod.POST, url);
            req.body(requestJson, ContentType.APPLICATION_JSON);

            var response = httpClient.execute(req);
            return parseResponse(response.text(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            LOGGER.error("sandbox runtime request failed: url={}, tool={}", baseUrl, toolName, e);
            return ToolCallResult.failed("Sandbox execution failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("error", "sandbox_error");
        }
    }

    public ToolCallResult pollTask(String taskId) {
        var startTime = System.currentTimeMillis();
        try {
            var url = baseUrl + "/tasks/" + taskId;
            var req = new HTTPRequest(HTTPMethod.GET, url);
            var response = httpClient.execute(req);
            return parseResponse(response.text(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            LOGGER.error("sandbox task poll failed: url={}, taskId={}", baseUrl, taskId, e);
            return ToolCallResult.failed("Failed to poll task: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult parseResponse(String responseBody, long durationMs) {
        try {
            var response = JSON.fromJSON(ExecuteResponse.class, responseBody);
            return switch (response.status) {
                case "completed" -> ToolCallResult.completed(response.result)
                        .withDuration(durationMs)
                        .withStats("sandboxStatus", "completed");
                case "failed" -> ToolCallResult.failed(response.result)
                        .withDuration(durationMs)
                        .withStats("sandboxStatus", "failed");
                case "timeout" -> ToolCallResult.failed("Sandbox execution timeout: " + response.result)
                        .withDuration(durationMs)
                        .withStats("sandboxStatus", "timeout");
                case "pending" -> ToolCallResult.pending(response.taskId, "Async task submitted: " + response.taskId)
                        .withDuration(durationMs)
                        .withStats("sandboxStatus", "pending")
                        .withStats("taskId", response.taskId);
                default -> ToolCallResult.failed("Unknown sandbox response: " + response.status)
                        .withDuration(durationMs);
            };
        } catch (Exception e) {
            return ToolCallResult.failed("Failed to parse sandbox response: " + e.getMessage())
                    .withDuration(durationMs);
        }
    }

    public void close() {
    }

    public static class ExecuteRequest {
        @Property(name = "tool")
        public String tool;
        @Property(name = "arguments")
        public String arguments;
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
}
