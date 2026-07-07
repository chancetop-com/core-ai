package ai.core.server.sandbox;

import ai.core.agent.ExecutionContext;
import ai.core.internal.http.CustomHTTPClientImpl;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxConstants;
import ai.core.tool.ToolCallResult;
import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class SandboxClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxClient.class);

    private static final HTTPClient SHARED_POLL_CLIENT = new PatchedHTTPClientBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .timeout(Duration.ofSeconds(3))
            .buildCached();

    private final String baseUrl;
    private final String ip;
    private final int port;
    private final HTTPClient httpClient;

    public SandboxClient(String ip, int port, int timeoutSeconds) {
        this.ip = ip;
        this.port = port;
        this.baseUrl = "http://" + ip + ":" + port;
        var timeoutMs = timeoutSeconds > 0 ? timeoutSeconds * 1000L : SandboxConstants.DEFAULT_TOOL_TIMEOUT_MS;
        this.httpClient = new PatchedHTTPClientBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .timeout(Duration.ofMillis(timeoutMs))
                .buildCached();
    }

    public void waitForReady(int maxWaitMs) {
        var url = baseUrl + "/health";
        var start = System.currentTimeMillis();
        try {
            while (System.currentTimeMillis() - start < maxWaitMs) {
                try {
                    var req = new HTTPRequest(HTTPMethod.GET, url);
                    var resp = SHARED_POLL_CLIENT.execute(req);
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
            throw new RuntimeException("sandbox runtime health check timed out after " + maxWaitMs + "ms: url=" + baseUrl);
        }
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
            throw e;
        }
    }

    public void materializeSkill(String name, String version, byte[] archiveBytes) {
        var url = baseUrl + "/skills/" + name;
        var req = new HTTPRequest(HTTPMethod.POST, url);
        if (version != null) {
            req.headers.put("X-Skill-Version", version);
        }
        req.body(archiveBytes, ContentType.parse("application/zip"));

        HTTPResponse response;
        try {
            response = httpClient.execute(req);
        } catch (Exception e) {
            LOGGER.error("materialize skill request failed: url={}, name={}", url, name, e);
            throw new RuntimeException("failed to materialize skill: " + name, e);
        }

        if (response.statusCode != 200 && response.statusCode != 204) {
            throw new RuntimeException("materialize skill failed: status=" + response.statusCode
                + ", body=" + response.text());
        }
        LOGGER.info("materialized skill to sandbox: name={}, version={}, size={}bytes", name, version, archiveBytes.length);
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

    public SandboxFile downloadFile(String path) {
        try {
            var url = baseUrl + "/files/content?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
            var req = new HTTPRequest(HTTPMethod.GET, url);
            var response = httpClient.execute(req);
            if (response.statusCode != 200) {
                throw new RuntimeException("sandbox file download failed: status=" + response.statusCode
                        + ", body=" + response.text());
            }

            var tempFile = Files.createTempFile("sandbox-artifact-", ".bin");
            Files.write(tempFile, response.body);
            var fileName = header(response, "X-File-Name");
            if (fileName == null || fileName.isBlank()) {
                fileName = java.nio.file.Path.of(path).getFileName().toString();
            }
            var contentType = response.contentType != null ? response.contentType.toString() : header(response, "Content-Type");
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            return new SandboxFile(tempFile, fileName, contentType, response.body.length);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write sandbox file to temp file", e);
        }
    }

    public void uploadFile(String path, byte[] content) {
        var url = baseUrl + "/files/upload?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        var req = new HTTPRequest(HTTPMethod.POST, url);
        req.body(content, ContentType.APPLICATION_OCTET_STREAM);
        var response = httpClient.execute(req);
        if (response.statusCode != 200) {
            throw new RuntimeException("sandbox file upload failed: status=" + response.statusCode
                    + ", body=" + response.text());
        }
        LOGGER.info("uploaded file to sandbox: path={}, size={}bytes", path, content.length);
    }

    private String header(HTTPResponse response, String name) {
        return response.headers.get(name);
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
        if (httpClient instanceof CustomHTTPClientImpl) {
            ((CustomHTTPClientImpl) httpClient).close();
        }
    }

    // ---- MCP server management ----

    public String startMcpServer(String id, String command, List<String> args, Map<String, String> env) {
        var request = new McpStartRequest();
        request.id = id;
        request.command = command;
        request.args = args != null ? args : List.of();
        request.env = env != null ? env : Map.of();

        var url = baseUrl + "/mcp/start";
        var req = new HTTPRequest(HTTPMethod.POST, url);
        req.body(JSON.toJSON(request), ContentType.APPLICATION_JSON);
        var response = httpClient.execute(req);

        if (response.statusCode != 200) {
            throw new RuntimeException("mcp start failed: status=" + response.statusCode
                + ", body=" + response.text());
        }
        LOGGER.info("started mcp server in sandbox: id={}, command={}", id, command);
        return id;
    }

    public void stopMcpServer(String id) {
        var request = new McpStopRequest();
        request.id = id;

        var url = baseUrl + "/mcp/stop";
        var req = new HTTPRequest(HTTPMethod.POST, url);
        req.body(JSON.toJSON(request), ContentType.APPLICATION_JSON);
        var response = httpClient.execute(req);

        if (response.statusCode != 200) {
            throw new RuntimeException("mcp stop failed: status=" + response.statusCode
                + ", body=" + response.text());
        }
        LOGGER.info("stopped mcp server in sandbox: id={}", id);
    }

    public String getMcpEndpoint() {
        return baseUrl + "/mcp";
    }

    public String getBaseUrl() {
        return baseUrl;
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
