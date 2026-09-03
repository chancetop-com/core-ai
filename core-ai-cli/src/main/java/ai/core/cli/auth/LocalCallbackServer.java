package ai.core.cli.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * author cyril
 * description
 * createTime  2026/6/8
 **/
public class LocalCallbackServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalCallbackServer.class);
    private static final Path PORT_FILE = Path.of(System.getProperty("user.home"), ".core-ai", "cli.port");

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        var bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
    private static String successPage() {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="utf-8"><title>Login Complete</title>
                <style>
                  body { font-family: -apple-system, sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background: #1a1a2e; color: #e0e0e0; }
                  .box { text-align: center; padding: 48px; }
                  h1 { color: #4ade80; margin-bottom: 8px; }
                  p { color: #888; }
                </style>
                </head>
                <body>
                <div class="box">
                  <h1>&#10003; Login Complete</h1>
                  <p>You may close this tab and return to the terminal.</p>
                </div>
                </body>
                </html>
                """;
    }

    private static String errorPage(String message) {
        var msg = message != null ? message : "Unknown error";
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head><meta charset=\"utf-8\"><title>Login Failed</title>\n"
                + "<style>\n"
                + "  body { font-family: -apple-system, sans-serif; display: flex; justify-content: center;"
                + " align-items: center; height: 100vh; margin: 0; background: #1a1a2e; color: #e0e0e0; }\n"
                + "  .box { text-align: center; padding: 48px; }\n"
                + "  h1 { color: #f87171; margin-bottom: 8px; }\n"
                + "  p { color: #888; }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"box\">\n"
                + "  <h1>&#10007; Login Failed</h1>\n"
                + "  <p>" + msg + "</p>\n"
                + "</div>\n"
                + "</body>\n"
                + "</html>";
    }

    private final HttpServer server;
    private final int port;
    private final CompletableFuture<String> apiKeyFuture = new CompletableFuture<>();

    public LocalCallbackServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            port = server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start local callback server", e);
        }
        writePortFile();
        LOGGER.debug("LocalCallbackServer started on port {}", port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var params = parseQuery(exchange.getRequestURI().getRawQuery());
            var apiKey = params.get("api_key");
            var error = params.get("error");

            if (error != null) {
                apiKeyFuture.completeExceptionally(new RuntimeException("authorization denied: " + error));
                sendHtml(exchange, errorPage(error));
                return;
            }

            if (apiKey != null && !apiKey.isBlank()) {
                sendHtml(exchange, successPage());
                apiKeyFuture.complete(apiKey);
                return;
            }

            sendHtml(exchange, errorPage("no api_key parameter"));
        } catch (Exception e) {
            apiKeyFuture.completeExceptionally(e);
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        var params = new HashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (var pair : rawQuery.split("&")) {
            var index = pair.indexOf('=');
            var key = index >= 0 ? pair.substring(0, index) : pair;
            var value = index >= 0 ? pair.substring(index + 1) : "";
            params.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    public int port() {
        return port;
    }

    /**
     * Blocks until the browser redirects back with an api_key, or times out.
     *
     * @return the API key string, or null on timeout
     */
    public String waitForApiKey(long timeoutSeconds) throws Exception {
        try {
            return apiKeyFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            server.stop(0);
            Files.deleteIfExists(PORT_FILE);
            LOGGER.debug("LocalCallbackServer stopped");
        } catch (Exception e) {
            LOGGER.warn("Failed to stop LocalCallbackServer: {}", e.getMessage());
        }
    }

    private void writePortFile() {
        try {
            Path parent = PORT_FILE.getParent();
            if (parent == null) return;
            Files.createDirectories(parent);
            Files.writeString(PORT_FILE, String.valueOf(port));
        } catch (IOException e) {
            LOGGER.warn("Failed to write port file: {}", e.getMessage());
        }
    }
}
