package ai.core.tool.tools;

import com.sun.net.httpserver.HttpServer;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class WebFetchToolDefaultMethodTest {
    @Test
    void defaultsToGetWhenMethodIsOmitted() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var method = new AtomicReference<String>();
        server.createContext("/ping", exchange -> {
            method.set(exchange.getRequestMethod());
            var body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/ping");
            var result = WebFetchTool.builder().build().execute(JSON.toJSON(args));

            assertEquals("GET", method.get());
            assertNotNull(result.getResult());
            assertTrue(result.getResult().contains("pong"));
        } finally {
            server.stop(0);
        }
    }
}
