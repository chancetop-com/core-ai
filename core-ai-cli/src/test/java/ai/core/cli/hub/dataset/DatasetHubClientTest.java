package ai.core.cli.hub.dataset;

import ai.core.cli.http.RemoteApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local wire contract: which URL and verb each operation uses, what it puts in the body, and how the payload text
 * comes back. Stubbed with a real HTTP server so paths, query parameters and json shapes are exercised for real; the
 * stub answers from the request alone, so no expectation depends on mutable test state.
 *
 * @author stephen
 */
class DatasetHubClientTest {
    private static final String FORBIDDEN_REF = "forbidden";
    private static final String LIST_BODY = "{\"session_id\":\"s-1\",\"datasets\":[{\"dataset_id\":\"id-1\","
            + "\"name\":\"menu-state\",\"type\":\"SESSION\",\"permission\":\"WRITE\",\"schema\":[]}]}";

    private final AtomicReference<Request> lastRequest = new AtomicReference<>();
    private HttpServer server;
    private DatasetHubClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new DatasetHubClient("http://127.0.0.1:" + server.getAddress().getPort(), "ctk_test", false, null);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void listReadsTheSessionBindings() {
        var view = client.datasets("s-1");

        assertEquals("GET", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets", sent().path);
        assertEquals("Bearer ctk_test", sent().authorization);
        assertEquals("cli", sent().clientHeader);
        assertEquals("s-1", view.sessionId);
        assertEquals("id-1", view.datasets.getFirst().datasetId);
    }

    @Test
    void stateGetSelectsFields() {
        client.getState("s-1", "menu-state", "a,b");

        assertEquals("GET", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/menu-state/state?fields=a%2Cb", sent().path);
    }

    @Test
    void stateSetSendsTheDataAsJsonObject() {
        client.saveState("s-1", "id-1", "{\"a\":1}");

        assertEquals("PUT", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/id-1/state", sent().path);
        assertEquals("{\"data\":\"{\\\"a\\\":1}\"}", sent().body);
    }

    @Test
    void statePatchPostsToThePatchEndpoint() {
        client.patchState("s-1", "id-1", "{\"a\":1}");

        assertEquals("POST", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/id-1/state/patch", sent().path);
    }

    @Test
    void recordsQueryOmitsUnsetParameters() {
        client.queryRecords("s-1", "id-1", new DatasetHubClient.RecordQuery("{\"status\":\"open\"}", null, null, null, 2, null));

        assertEquals("GET", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/id-1/records?filter=%7B%22status%22%3A%22open%22%7D&limit=2", sent().path);
    }

    @Test
    void recordsInsertPostsToTheCollection() {
        client.insertRecord("s-1", "id-1", "{\"a\":1}");

        assertEquals("POST", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/id-1/records", sent().path);
    }

    @Test
    void recordsUpdatePostsToTheRecordUrl() {
        client.updateRecord("s-1", "id-1", "r-1", "{\"a\":1}");

        assertEquals("POST", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/id-1/records/r-1", sent().path);
    }

    @Test
    void recordsDeleteReadsThePayloadFromTheDeleteResponse() {
        var response = client.deleteRecord("s-1", "id-1", "r-1");

        assertEquals("DELETE", sent().method);
        assertEquals("/api/hub/sessions/s-1/datasets/id-1/records/r-1", sent().path);
        assertEquals("records.delete", response.op);
        assertEquals("{\"status\":\"deleted\"}", response.payload);
    }

    @Test
    void serverErrorsPropagateAsApiExceptions() {
        var error = assertThrows(RemoteApiException.class, () -> client.saveState("s-1", FORBIDDEN_REF, "{}"));

        assertEquals(403, error.statusCode);
        assertEquals("permission denied", error.getMessage());
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var uri = exchange.getRequestURI();
        var path = uri.getPath();
        var query = uri.getRawQuery();
        lastRequest.set(new Request(exchange.getRequestMethod(), query == null ? path : path + '?' + query,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("x-core-ai-client"), body));
        if (path.contains("/" + FORBIDDEN_REF + "/")) {
            respond(exchange, 403, "{\"message\":\"permission denied\"}");
        } else if (path.endsWith("/datasets")) {
            respond(exchange, 200, LIST_BODY);
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, opBody("records.delete", "{\\\"status\\\":\\\"deleted\\\"}"));
        } else if (path.endsWith("/state")) {
            respond(exchange, 200, opBody("state.get", "{\\\"state\\\":{}}"));
        } else {
            respond(exchange, 200, opBody("records.query", "{\\\"records\\\":[]}"));
        }
    }

    private String opBody(String op, String payload) {
        return "{\"op\":\"" + op + "\",\"payload\":\"" + payload + "\"}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Request sent() {
        var request = lastRequest.get();
        assertTrue(request != null, "no request reached the server");
        return request;
    }

    private record Request(String method, String path, String authorization, String clientHeader, String body) {
    }
}
