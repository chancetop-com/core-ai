package ai.core.cli.a2a;

import ai.core.a2a.A2ARunManager;
import ai.core.cli.a2a.handler.AgentCardHandler;
import ai.core.cli.a2a.handler.CapabilitiesHandler;
import ai.core.cli.a2a.handler.MessageHandler;
import ai.core.cli.a2a.handler.TaskHandler;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class A2AServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AServer.class);

    private static final HttpString ACCESS_CONTROL_ALLOW_ORIGIN = new HttpString("Access-Control-Allow-Origin");
    private static final HttpString ACCESS_CONTROL_ALLOW_METHODS = new HttpString("Access-Control-Allow-Methods");
    private static final HttpString ACCESS_CONTROL_ALLOW_HEADERS = new HttpString("Access-Control-Allow-Headers");
    private static final HttpString ACCESS_CONTROL_MAX_AGE = new HttpString("Access-Control-Max-Age");

    private final Undertow server;
    private final A2ARunManager runManager;
    private final int port;

    public A2AServer(int port, A2ARunManager runManager) {
        silenceUndertowLogs();
        this.port = port;
        this.runManager = runManager;

        var agentCardHandler = new AgentCardHandler(runManager);
        var messageHandler = new MessageHandler(runManager);
        var taskHandler = new TaskHandler(runManager);
        var capabilitiesHandler = new CapabilitiesHandler();

        var pathHandler = new PathTemplateHandler(staticFileHandler());
        pathHandler.add("/.well-known/agent-card.json", agentCardHandler);
        pathHandler.add("/message/send", messageHandler);
        pathHandler.add("/tasks/{taskId}", taskHandler);
        pathHandler.add("/tasks/{taskId}/cancel", taskHandler);
        pathHandler.add("/tasks/{taskId}/message/send", taskHandler);
        pathHandler.add("/api/capabilities", capabilitiesHandler);

        this.server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(corsWrapper(pathHandler))
                .build();
    }

    public void start() {
        server.start();
        LOGGER.info("A2A server started on port {}", port);
    }

    private static void silenceUndertowLogs() {
        for (String name : new String[]{"io.undertow", "org.xnio", "org.jboss.threads"}) {
            java.util.logging.Logger.getLogger(name).setLevel(java.util.logging.Level.OFF);
        }
    }

    public void stop() {
        runManager.close();
        server.stop();
        LOGGER.info("A2A server stopped");
    }

    public int port() {
        return port;
    }

    private HttpHandler staticFileHandler() {
        var resourceManager = new ClassPathResourceManager(Thread.currentThread().getContextClassLoader(), "web");
        var resourceHandler = new ResourceHandler(resourceManager);
        resourceHandler.setWelcomeFiles("index.html");
        return resourceHandler;
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
