package ai.core.cli.auth;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static void sendHtml(io.undertow.server.HttpServerExchange exchange, String html) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getResponseSender().send(html);
    }

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

    private final Undertow server;
    private final int port;
    private final CompletableFuture<String> apiKeyFuture = new CompletableFuture<>();

    public LocalCallbackServer() {
        this.server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(exchange -> {
                    try {
                        var params = exchange.getQueryParameters();
                        var apiKey = params.containsKey("api_key") ? params.get("api_key").getFirst() : null;
                        var error = params.containsKey("error") ? params.get("error").getFirst() : null;

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
                })
                .setWorkerThreads(1)
                .build();
        server.start();
        this.port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        writePortFile();
        LOGGER.debug("LocalCallbackServer started on port {}", port);
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
            server.stop();
            Files.deleteIfExists(PORT_FILE);
            LOGGER.debug("LocalCallbackServer stopped");
        } catch (Exception e) {
            LOGGER.warn("Failed to stop LocalCallbackServer: {}", e.getMessage());
        }
    }

    private void writePortFile() {
        try {
            Files.createDirectories(PORT_FILE.getParent());
            Files.writeString(PORT_FILE, String.valueOf(port));
        } catch (IOException e) {
            LOGGER.warn("Failed to write port file: {}", e.getMessage());
        }
    }
}
