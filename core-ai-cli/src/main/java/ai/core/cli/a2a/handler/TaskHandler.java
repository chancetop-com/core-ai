package ai.core.cli.a2a.handler;

import ai.core.a2a.A2ARunManager;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.PathTemplateMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * @author stephen
 */
public class TaskHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskHandler.class);

    private static String extractDecision(Message msg) {
        if (msg.parts == null) return null;
        for (var part : msg.parts) {
            if (!"data".equals(part.type) || part.data == null) continue;
            var d = part.data.get("decision");
            if (d != null) return String.valueOf(d);
        }
        return null;
    }

    private static String extractCallId(Message msg) {
        if (msg.parts == null) return null;
        for (var part : msg.parts) {
            if (!"data".equals(part.type) || part.data == null) continue;
            var c = part.data.get("call_id");
            if (c != null) return String.valueOf(c);
        }
        return null;
    }

    private final A2ARunManager runManager;

    public TaskHandler(A2ARunManager runManager) {
        this.runManager = runManager;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        var match = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
        var taskId = match != null ? match.getParameters().get("taskId") : null;
        if (taskId == null) {
            exchange.setStatusCode(400);
            sendJson(exchange, "{\"error\":\"taskId required\"}");
            return;
        }

        try {
            var path = exchange.getRelativePath();
            if (path.endsWith("/cancel") && Methods.POST.equals(exchange.getRequestMethod())) {
                handleCancel(exchange, taskId);
            } else if (path.endsWith("/message/send") && Methods.POST.equals(exchange.getRequestMethod())) {
                handleResume(exchange, taskId);
            } else if (Methods.GET.equals(exchange.getRequestMethod())) {
                handleGet(exchange, taskId);
            } else {
                exchange.setStatusCode(405);
                exchange.getResponseSender().send("Method not allowed");
            }
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (IllegalStateException e) {
            exchange.setStatusCode(409);
            sendJson(exchange, "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            LOGGER.error("error handling task request", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private void handleGet(HttpServerExchange exchange, String taskId) {
        var task = runManager.getTask(taskId);
        if (task == null) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"task not found\"}");
            return;
        }
        sendJson(exchange, JsonUtil.toJson(task));
    }

    private void handleResume(HttpServerExchange exchange, String taskId) throws IOException {
        var body = readBody(exchange);
        var request = JsonUtil.fromJson(SendMessageRequest.class, body);

        var decision = "approve";
        String callId = null;

        if (request.message != null) {
            var text = request.message.extractText().trim().toLowerCase(Locale.ROOT);
            if ("deny".equals(text) || "reject".equals(text)) {
                decision = "deny";
            }
            var d = extractDecision(request.message);
            if (d != null) decision = d;
            var c = extractCallId(request.message);
            if (c != null) callId = c;
        }

        runManager.resumeTask(taskId, decision, callId);
        sendJson(exchange, "{\"status\":\"resumed\"}");
    }

    private void handleCancel(HttpServerExchange exchange, String taskId) {
        runManager.cancelTask(taskId);
        sendJson(exchange, "{\"status\":\"canceled\"}");
    }

    private String readBody(HttpServerExchange exchange) throws IOException {
        exchange.startBlocking();
        return new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }
}
