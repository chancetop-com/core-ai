package ai.core.a2a;

import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.SendMessageResponse;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import ai.core.utils.JsonUtil;
import core.framework.http.EventSource;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpA2AClientTest {
    @Test
    void getAgentCardUsesTenantDiscoveryPath() {
        var http = new FakeHTTPClient();
        var card = new AgentCard();
        card.name = "reviewer";
        http.responses.add(json(card));
        var client = client(http);

        var response = client.getAgentCard();

        assertEquals("reviewer", response.name);
        assertEquals(HTTPMethod.GET, http.lastRequest.method);
        assertEquals("http://server/api/a2a/agents/agent-1/.well-known/agent-card.json", http.lastRequest.uri);
        assertEquals("Bearer cai_test", http.lastRequest.headers.get("Authorization"));
    }

    @Test
    void getAgentCardFallsBackToRootDiscoveryPath() {
        var http = new FakeHTTPClient();
        var card = new AgentCard();
        card.name = "local";
        http.responses.add(response(404, "{}"));
        http.responses.add(json(card));
        var client = client(http);

        var response = client.getAgentCard();

        assertEquals("local", response.name);
        assertEquals("http://server/api/a2a/agents/agent-1/.well-known/agent-card.json", http.requests.getFirst().uri);
        assertEquals("http://server/api/a2a/.well-known/agent-card.json", http.lastRequest.uri);
    }

    @Test
    void sendPostsTenantToUnifiedEndpoint() {
        var http = new FakeHTTPClient();
        var task = task("task-1", TaskState.COMPLETED);
        http.responses.add(json(SendMessageResponse.ofTask(task)));
        var client = client(http);

        var result = client.send(message("hello"));

        assertEquals("task-1", result.task.id);
        assertEquals(HTTPMethod.POST, http.lastRequest.method);
        assertEquals("http://server/api/a2a/message:send", http.lastRequest.uri);
        assertTrue(new String(http.lastRequest.body, StandardCharsets.UTF_8).contains("\"tenant\":\"agent-1\""));
    }

    @Test
    void streamPostsTenantAndPublishesEvents() throws InterruptedException {
        var http = new FakeHTTPClient();
        var task = task("task-1", TaskState.WORKING);
        var terminal = new TaskStatusUpdateEvent();
        terminal.taskId = "task-1";
        terminal.status = TaskStatus.of(TaskState.COMPLETED);
        http.eventSource = eventSource(
                StreamResponse.ofTask(task),
                StreamResponse.ofStatusUpdate(terminal)
        );
        var client = client(http);
        var subscriber = new CollectingSubscriber();

        client.stream(message("hello")).subscribe(subscriber);

        assertTrue(subscriber.completed.await(5, TimeUnit.SECONDS));
        assertEquals(2, subscriber.events.size());
        assertEquals("task-1", subscriber.events.getFirst().task.id);
        assertEquals(TaskState.COMPLETED, subscriber.events.get(1).statusUpdate.status.state);
        assertEquals("http://server/api/a2a/message:stream", http.lastRequest.uri);
        assertEquals("text/event-stream", http.lastRequest.headers.get("Accept"));
        assertTrue(new String(http.lastRequest.body, StandardCharsets.UTF_8).contains("\"tenant\":\"agent-1\""));
    }

    @Test
    void streamReportsHttpFailure() throws InterruptedException {
        var http = new FakeHTTPClient();
        http.eventSource = eventSource(401);
        var client = client(http);
        var subscriber = new CollectingSubscriber();

        client.stream(message("hello")).subscribe(subscriber);

        assertTrue(subscriber.completed.await(5, TimeUnit.SECONDS));
        assertTrue(subscriber.error instanceof IllegalStateException);
        assertTrue(subscriber.error.getMessage().contains("statusCode=401"));
    }

    @Test
    void getsAndCancelsTasks() {
        var http = new FakeHTTPClient();
        http.responses.add(json(task("task-1", TaskState.WORKING)));
        http.responses.add(json(task("task-1", TaskState.CANCELED)));
        var client = client(http);
        var getRequest = new GetTaskRequest();
        getRequest.id = "task-1";
        var cancelRequest = new CancelTaskRequest();
        cancelRequest.id = "task-1";

        assertEquals(TaskState.WORKING, client.getTask(getRequest).status.state);
        assertEquals("http://server/api/a2a/tasks/task-1", http.requests.getFirst().uri);
        assertEquals(TaskState.CANCELED, client.cancelTask(cancelRequest).status.state);
        assertEquals("http://server/api/a2a/tasks/task-1/cancel", http.lastRequest.uri);
    }

    private HttpA2AClient client(FakeHTTPClient http) {
        return HttpA2AClient.builder()
                .baseUrl("http://server/api/a2a/")
                .tenant("agent-1")
                .bearerToken("cai_test")
                .httpClient(http)
                .build();
    }

    private SendMessageRequest message(String text) {
        var request = new SendMessageRequest();
        request.message = ai.core.api.a2a.Message.user(text);
        return request;
    }

    private Task task(String id, TaskState state) {
        var task = new Task();
        task.id = id;
        task.status = TaskStatus.of(state);
        return task;
    }

    private HTTPResponse json(Object body) {
        return response(200, JsonUtil.toJson(body));
    }

    private HTTPResponse response(int statusCode, String body) {
        return new HTTPResponse(statusCode,
                Map.of("Content-Type", "application/a2a+json"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private EventSource eventSource(StreamResponse... responses) {
        var body = new StringBuilder();
        for (var response : responses) {
            body.append("data: ").append(JsonUtil.toJson(response)).append("\n\n");
        }
        return eventSource(200, body.toString());
    }

    private EventSource eventSource(int statusCode) {
        return eventSource(statusCode, "");
    }

    private EventSource eventSource(int statusCode, String body) {
        return new EventSource(statusCode,
                Map.of("Content-Type", "text/event-stream"),
                ResponseBody.create(body, MediaType.parse("text/event-stream")),
                0,
                0);
    }

    private static final class FakeHTTPClient implements HTTPClient {
        final Queue<HTTPResponse> responses = new ArrayDeque<>();
        final List<HTTPRequest> requests = new ArrayList<>();
        HTTPRequest lastRequest;
        EventSource eventSource;

        @Override
        public HTTPResponse execute(HTTPRequest request) {
            requests.add(request);
            lastRequest = request;
            return responses.remove();
        }

        @Override
        public EventSource sse(HTTPRequest request) {
            requests.add(request);
            lastRequest = request;
            return eventSource;
        }
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        final List<A2AStreamEvent> events = new ArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }
    }
}
