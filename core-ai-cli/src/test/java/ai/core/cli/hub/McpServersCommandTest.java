package ai.core.cli.hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The command boundary: a response body that is compressed, binary or not JSON must end as an exit
 * code, never as an escaping parser exception.
 *
 * @author stephen
 */
class McpServersCommandTest {
    private static final String JSON = "{\"servers\":[{\"name\":\"stub\"}]}";

    private static void respond(HttpExchange exchange, int status, byte[] body, String contentEncoding) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (contentEncoding != null) {
            exchange.getResponseHeaders().set("Content-Encoding", contentEncoding);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] body) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(body);
        }
        return out.toByteArray();
    }

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void route(String path, int status, byte[] body, String contentEncoding) {
        server.createContext(path, exchange -> respond(exchange, status, body, contentEncoding));
    }

    private McpServersCommand command() {
        var command = new McpServersCommand();
        command.options = new HubGlobalOptions();
        command.options.server = "http://127.0.0.1:" + server.getAddress().getPort();
        command.options.apiKey = "test-key";
        command.options.quiet = true;
        return command;
    }

    @Test
    void listsServersWhenBodyIsGzipWithoutContentEncoding() throws IOException {
        route("/api/mcp-hub/servers", 200, gzip(bytes(JSON)), null);

        assertEquals(HubExitCodes.SUCCESS, command().call());
    }

    @Test
    void failsCleanlyWhenBodyIsNotJson() {
        var body = "{\"servers\":[]," + (char) 0x1F + "\"extra\":1}";
        route("/api/mcp-hub/servers", 200, bytes(body), null);

        assertEquals(HubExitCodes.TOOL_ERROR, command().call());
    }

    @Test
    void failsCleanlyWhenBodyIsBinary() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        route("/api/mcp-hub/servers", 200, png, null);

        assertEquals(HubExitCodes.TOOL_ERROR, command().call());
    }
}
