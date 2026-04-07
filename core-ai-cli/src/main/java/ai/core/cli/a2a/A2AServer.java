package ai.core.cli.a2a;

import ai.core.a2a.A2ARunManager;
import ai.core.cli.a2a.handler.AgentCardHandler;
import ai.core.cli.a2a.handler.CapabilitiesHandler;
import ai.core.cli.a2a.handler.LocalAgentHandler;
import ai.core.cli.a2a.handler.LocalSkillHandler;
import ai.core.cli.a2a.handler.LocalToolHandler;
import ai.core.cli.a2a.handler.MessageHandler;
import ai.core.cli.a2a.handler.TaskHandler;
import ai.core.cli.a2a.handler.chat.ChatSessionActionHandler;
import ai.core.cli.a2a.handler.chat.ChatSessionCreateHandler;
import ai.core.cli.a2a.handler.chat.ChatSessionSSEHandler;
import ai.core.cli.session.LocalChatSessionManager;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * @author stephen
 */
public class A2AServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AServer.class);

    private static final HttpString ACCESS_CONTROL_ALLOW_ORIGIN = new HttpString("Access-Control-Allow-Origin");
    private static final HttpString ACCESS_CONTROL_ALLOW_METHODS = new HttpString("Access-Control-Allow-Methods");
    private static final HttpString ACCESS_CONTROL_ALLOW_HEADERS = new HttpString("Access-Control-Allow-Headers");
    private static final HttpString ACCESS_CONTROL_MAX_AGE = new HttpString("Access-Control-Max-Age");

    private static void silenceUndertowLogs() {
        for (String name : new String[]{"io.undertow", "org.xnio", "org.jboss.threads"}) {
            java.util.logging.Logger.getLogger(name).setLevel(java.util.logging.Level.OFF);
        }
    }

    private final Undertow server;
    private final A2ARunManager runManager;
    private final LocalChatSessionManager chatSessionManager;
    private final int port;

    public A2AServer(int port, A2ARunManager runManager, LocalChatSessionManager chatSessionManager, Path webDir) {
        silenceUndertowLogs();
        this.port = port;
        this.runManager = runManager;
        this.chatSessionManager = chatSessionManager;

        var agentCardHandler = new AgentCardHandler(runManager);
        var messageHandler = new MessageHandler(runManager);
        var taskHandler = new TaskHandler(runManager);
        var capabilitiesHandler = new CapabilitiesHandler();

        // Chat session REST API handlers (for web frontend compatibility)
        var chatSessionCreateHandler = new ChatSessionCreateHandler(chatSessionManager);
        var chatSessionActionHandler = new ChatSessionActionHandler(chatSessionManager);
        var chatSessionSSEHandler = new ChatSessionSSEHandler(chatSessionManager);
        var localAgentHandler = new LocalAgentHandler(runManager.getAgentFactory());
        var localToolHandler = new LocalToolHandler();
        var localSkillHandler = new LocalSkillHandler();

        var apiPathHandler = new PathTemplateHandler(notFoundHandler());
        apiPathHandler.add("/.well-known/agent-card.json", agentCardHandler);
        apiPathHandler.add("/message/send", messageHandler);
        apiPathHandler.add("/tasks/{taskId}", taskHandler);
        apiPathHandler.add("/tasks/{taskId}/cancel", taskHandler);
        apiPathHandler.add("/tasks/{taskId}/message/send", taskHandler);
        apiPathHandler.add("/api/capabilities", capabilitiesHandler);

        // Chat session web API endpoints (replaces old A2A-style session endpoints)
        apiPathHandler.add("/api/sessions", chatSessionCreateHandler);
        apiPathHandler.add("/api/sessions/{sessionId}", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/{sessionId}/history", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/{sessionId}/messages", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/{sessionId}/approve", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/{sessionId}/cancel", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/{sessionId}/tools", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/{sessionId}/skills", chatSessionActionHandler);
        apiPathHandler.add("/api/sessions/events", chatSessionSSEHandler);

        // Local agent/tools/skills endpoints (for web frontend agent picker)
        apiPathHandler.add("/api/agents", localAgentHandler);
        apiPathHandler.add("/api/agents/{id}", localAgentHandler);
        apiPathHandler.add("/api/tools", localToolHandler);
        apiPathHandler.add("/api/skills", localSkillHandler);

        var staticHandler = staticFileHandler(webDir);

        var spaHandler = (HttpHandler) exchange -> {
            var path = exchange.getRequestPath();
            if ("/".equals(path) || path.isEmpty()) {
                exchange.setStatusCode(302);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/chat");
                exchange.endExchange();
                return;
            }
            // SPA fallback: serve index.html for frontend routes (not API or static assets with extension)
            if (!path.startsWith("/api/") && !path.startsWith("/message") && !path.startsWith("/tasks") && !path.startsWith("/.well-known") && !path.contains(".")) {
                exchange.setStatusCode(200);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html");
                exchange.getResponseSender().send(loadIndexHtml(webDir));
                return;
            }
            apiPathHandler.handleRequest(exchange);
        };

        this.server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(corsWrapper(exchange -> {
                    var path = exchange.getRequestPath();
                    // Static assets: let ResourceHandler serve them
                    if (path.contains(".")) {
                        staticHandler.handleRequest(exchange);
                    } else {
                        spaHandler.handleRequest(exchange);
                    }
                }))
                .build();
    }

    public void start() {
        server.start();
        LOGGER.info("A2A server started on port {}", port);
    }

    public void stop() {
        chatSessionManager.closeAll();
        runManager.close();
        server.stop();
        LOGGER.info("A2A server stopped");
    }

    private HttpHandler staticFileHandler(Path webDir) {
        ResourceHandler resourceHandler;
        if (webDir != null) {
            LOGGER.info("serving frontend from local directory: {}", webDir);
            resourceHandler = new ResourceHandler(new FileResourceManager(webDir.toFile()));
        } else {
            resourceHandler = new ResourceHandler(new ClassPathResourceManager(Thread.currentThread().getContextClassLoader(), "web"));
        }
        resourceHandler.setWelcomeFiles("index.html");
        return resourceHandler;
    }

    private HttpHandler notFoundHandler() {
        return exchange -> {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("{\"error\":\"Not Found\"}");
        };
    }

    private static String cachedIndexHtml;

    private String loadIndexHtml(Path webDir) {
        if (cachedIndexHtml != null) {
            return cachedIndexHtml;
        }
        if (webDir != null) {
            var file = webDir.resolve("index.html").toFile();
            if (file.exists()) {
                try {
                    cachedIndexHtml = java.nio.file.Files.readString(file.toPath());
                    return cachedIndexHtml;
                } catch (Exception e) {
                    LOGGER.warn("failed to load index.html: {}", e.getMessage());
                }
            }
        }
        // Fallback to classpath
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream("web/index.html")) {
            if (is != null) {
                cachedIndexHtml = new String(is.readAllBytes());
                return cachedIndexHtml;
            }
        } catch (Exception e) {
            LOGGER.warn("failed to load index.html from classpath: {}", e.getMessage());
        }
        cachedIndexHtml = "<!DOCTYPE html><html><body><h1>index.html not found</h1></body></html>";
        return cachedIndexHtml;
    }

    private HttpHandler corsWrapper(HttpHandler next) {
        return exchange -> {
            exchange.getResponseHeaders().put(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            exchange.getResponseHeaders().put(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().put(ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Accept, Authorization");
            exchange.getResponseHeaders().put(ACCESS_CONTROL_MAX_AGE, "86400");

            if (Methods.OPTIONS.equals(exchange.getRequestMethod())) {
                exchange.setStatusCode(204);
                exchange.endExchange();
                return;
            }

            next.handleRequest(exchange);
        };
    }
}
