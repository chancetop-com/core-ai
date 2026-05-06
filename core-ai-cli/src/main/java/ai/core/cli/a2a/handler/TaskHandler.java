package ai.core.cli.a2a.handler;

import ai.core.a2a.A2ARunManager;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.PathTemplateMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * @author stephen
 */
public class TaskHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskHandler.class);

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
            sendError(exchange, "taskId required");
            return;
        }

        try {
            var path = exchange.getRelativePath();
            if (path.endsWith(":cancel") && Methods.POST.equals(exchange.getRequestMethod())) {
                taskId = taskId.substring(0, taskId.length() - ":cancel".length());
                handleCancel(exchange, taskId);
            } else if (Methods.GET.equals(exchange.getRequestMethod())) {
                handleGet(exchange, taskId);
            } else {
                exchange.setStatusCode(405);
                exchange.getResponseSender().send("Method not allowed");
            }
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(404);
            sendError(exchange, e.getMessage());
        } catch (IllegalStateException e) {
            exchange.setStatusCode(409);
            sendError(exchange, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("error handling task request", e);
            exchange.setStatusCode(500);
            sendError(exchange, "internal server error");
        }
    }

    private void handleGet(HttpServerExchange exchange, String taskId) {
        var task = runManager.getTask(taskId);
        if (task == null) {
            exchange.setStatusCode(404);
            sendError(exchange, "task not found");
            return;
        }
        sendJson(exchange, JsonUtil.toJson(task));
    }

    private void handleCancel(HttpServerExchange exchange, String taskId) {
        runManager.cancelTask(taskId);
        sendJson(exchange, JsonUtil.toJson(runManager.getTask(taskId)));
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/a2a+json");
        exchange.getResponseSender().send(json);
    }

    private void sendError(HttpServerExchange exchange, String message) {
        sendJson(exchange, JsonUtil.toJson(Map.of("error", message != null ? message : "unknown error")));
    }

}
