package ai.core.server.sandbox.terminal;

import core.framework.api.json.Property;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPClientException;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * HTTP client for the sandbox runtime's interactive terminal endpoints.
 * <p>
 * Error handling is keyed ONLY on status code, never on response body or
 * content-type: the runtime's error responses are written via Go's
 * http.Error, so they may carry a text/plain content-type even when the body
 * looks like JSON, and the input/size error responses may have no body at
 * all.
 *
 * @author xander
 */
public class SandboxTerminalClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxTerminalClient.class);

    private final String baseUrl;
    private final HTTPClient httpClient;

    public SandboxTerminalClient(String ip, int port) {
        this.baseUrl = "http://" + ip + ":" + port;
        this.httpClient = HTTPClient.builder()
                .connectTimeout(Duration.ofSeconds(3))
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    public CreateResult create(String clientId, int rows, int cols) {
        var request = new CreateRequest();
        request.clientId = clientId;
        request.rows = rows;
        request.cols = cols;

        var req = new HTTPRequest(HTTPMethod.POST, baseUrl + "/terminal");
        req.body(JSON.toJSON(request), ContentType.APPLICATION_JSON);
        var response = execute(req);

        if (response.statusCode == 429) {
            throw new TerminalBusyException("terminal runtime busy: clientId=" + clientId);
        }
        if (response.statusCode != 200) {
            throw new TerminalRuntimeUnavailableException("terminal create failed: status=" + response.statusCode);
        }
        return parseCreateResponse(response);
    }

    public void input(String terminalId, String dataBase64) {
        var request = new InputRequest();
        request.dataBase64 = dataBase64;

        var req = new HTTPRequest(HTTPMethod.POST, baseUrl + "/terminal/" + terminalId + "/input");
        req.body(JSON.toJSON(request), ContentType.APPLICATION_JSON);
        var response = execute(req);
        checkNoContentOrGone(response, terminalId);
    }

    public void resize(String terminalId, int rows, int cols) {
        var request = new ResizeRequest();
        request.rows = rows;
        request.cols = cols;

        var req = new HTTPRequest(HTTPMethod.PUT, baseUrl + "/terminal/" + terminalId + "/size");
        req.body(JSON.toJSON(request), ContentType.APPLICATION_JSON);
        var response = execute(req);
        checkNoContentOrGone(response, terminalId);
    }

    public void close(String terminalId) {
        var req = new HTTPRequest(HTTPMethod.DELETE, baseUrl + "/terminal/" + terminalId);
        var response = execute(req);
        if (response.statusCode != 204) {
            throw new TerminalRuntimeUnavailableException("terminal close failed: status=" + response.statusCode);
        }
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public boolean health() {
        try {
            var req = new HTTPRequest(HTTPMethod.GET, baseUrl + "/health");
            var response = httpClient.execute(req);
            return response.statusCode == 200;
        } catch (Exception e) {
            LOGGER.debug("terminal runtime health check failed: baseUrl={}, error={}", baseUrl, e.getMessage());
            return false;
        }
    }

    public String eventsUrl(String terminalId) {
        return baseUrl + "/terminal/" + terminalId + "/events";
    }

    private CreateResult parseCreateResponse(HTTPResponse response) {
        CreateResponse body;
        try {
            body = JSON.fromJSON(CreateResponse.class, response.text());
        } catch (UncheckedIOException e) {
            throw new TerminalRuntimeUnavailableException("malformed terminal create response", e);
        }
        if (body.terminalId == null) {
            throw new TerminalRuntimeUnavailableException("terminal create response missing terminal_id");
        }
        return new CreateResult(body.terminalId, Boolean.TRUE.equals(body.recovered));
    }

    private void checkNoContentOrGone(HTTPResponse response, String terminalId) {
        if (response.statusCode == 410 || response.statusCode == 404) {
            throw new TerminalGoneException("terminal gone: terminalId=" + terminalId + ", status=" + response.statusCode);
        }
        if (response.statusCode != 204) {
            throw new TerminalRuntimeUnavailableException("terminal request failed: status=" + response.statusCode);
        }
    }

    // wraps connect failures/timeouts and any 5xx into TerminalRuntimeUnavailableException;
    // all other status codes (2xx/4xx) are returned as-is for callers to interpret.
    private HTTPResponse execute(HTTPRequest req) {
        HTTPResponse response;
        try {
            response = httpClient.execute(req);
        } catch (HTTPClientException e) {
            throw new TerminalRuntimeUnavailableException("terminal runtime unreachable: " + e.getMessage(), e);
        }
        if (response.statusCode >= 500) {
            throw new TerminalRuntimeUnavailableException("terminal runtime server error: status=" + response.statusCode);
        }
        return response;
    }

    public record CreateResult(String terminalId, boolean recovered) {
    }

    public static class CreateRequest {
        @Property(name = "client_id")
        public String clientId;
        @Property(name = "rows")
        public int rows;
        @Property(name = "cols")
        public int cols;
    }

    public static class CreateResponse {
        @Property(name = "terminal_id")
        public String terminalId;
        @Property(name = "recovered")
        public Boolean recovered;
    }

    public static class InputRequest {
        @Property(name = "data_base64")
        public String dataBase64;
    }

    public static class ResizeRequest {
        @Property(name = "rows")
        public int rows;
        @Property(name = "cols")
        public int cols;
    }
}
