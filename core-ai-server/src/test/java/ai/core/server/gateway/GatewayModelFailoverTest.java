package ai.core.server.gateway;

import ai.core.agent.CancellationException;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.providers.LLMStreamException;
import ai.core.llm.streaming.DefaultStreamingCallback;
import core.framework.http.HTTPClientException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayModelFailoverTest {
    @Test
    void retryableClassifiesProviderFailures() {
        assertTrue(GatewayModelFailover.retryable(statusError(503)));
        assertTrue(GatewayModelFailover.retryable(statusError(500)));
        assertTrue(GatewayModelFailover.retryable(statusError(429)));
        assertTrue(GatewayModelFailover.retryable(statusError(408)));
        assertTrue(GatewayModelFailover.retryable(statusError(401)));
        assertTrue(GatewayModelFailover.retryable(statusError(403)));
        assertFalse(GatewayModelFailover.retryable(statusError(400)));
        assertFalse(GatewayModelFailover.retryable(statusError(404)));
        assertFalse(GatewayModelFailover.retryable(statusError(413)));
        assertTrue(GatewayModelFailover.retryable(new SocketTimeoutException("read timed out")));
        assertTrue(GatewayModelFailover.retryable(new RuntimeException("sse request failed, error=connection reset")));
        assertFalse(GatewayModelFailover.retryable(new HTTPClientException("uri is invalid, uri=::bad", "INVALID_URL")));
        assertFalse(GatewayModelFailover.retryable(new CancellationException()));
        assertTrue(GatewayModelFailover.retryable(new LLMStreamException("upstream overloaded, retry later")));
        assertFalse(GatewayModelFailover.retryable(new LLMStreamException("invalid request: context length exceeded")));
        assertFalse(GatewayModelFailover.retryable(new RuntimeException("no enabled gateway model matches model: x")));
    }

    @Test
    void outputTrackingCallbackMarksDeliveredOutput() {
        var emptyChunk = new GatewayModelFailover.OutputTrackingCallback(new DefaultStreamingCallback(), false);
        emptyChunk.onChunk("");
        assertFalse(emptyChunk.outputEmitted());
        emptyChunk.onChunk("x");
        assertTrue(emptyChunk.outputEmitted());

        // agent turns ignore raw frames, so an SSE role-only chunk must not block failover
        var invisibleRawData = new GatewayModelFailover.OutputTrackingCallback(new DefaultStreamingCallback(), false);
        invisibleRawData.onRawData("{\"choices\":[]}");
        assertFalse(invisibleRawData.outputEmitted());

        var passthroughRawData = new GatewayModelFailover.OutputTrackingCallback(new DefaultStreamingCallback(), true);
        passthroughRawData.onRawData("{\"choices\":[]}");
        assertTrue(passthroughRawData.outputEmitted());

        var completed = new GatewayModelFailover.OutputTrackingCallback(new DefaultStreamingCallback(), false);
        completed.onComplete();
        assertTrue(completed.outputEmitted());
    }

    @Test
    void requestSnapshotRestoresRewrittenFieldsAndMessages() {
        var request = CompletionRequest.of(List.of(Message.of(RoleType.SYSTEM, "sys"), Message.of(RoleType.USER, "hi")),
                null, 0.2, "alias", null);
        request.setEndpoint("responses");
        request.responseFormat = ResponseFormat.jsonObject();
        request.reasoningEffort = ReasoningEffort.HIGH;
        request.setReasoningEffortValue("high");
        var snapshot = GatewayModelFailover.RequestSnapshot.capture(request);

        request.model = "upstream";
        request.setEndpoint(null);
        request.temperature = 1.0;
        request.responseFormat = null;
        request.reasoningEffort = null;
        request.setReasoningEffortValue(null);
        request.messages = List.of(Message.of(RoleType.USER, "changed"));

        snapshot.restore(request);

        assertEquals("alias", request.model);
        assertEquals("responses", request.getEndpoint());
        assertEquals(0.2, request.temperature);
        assertEquals(ReasoningEffort.HIGH, request.reasoningEffort);
        assertEquals("high", request.getReasoningEffortValue());
        assertEquals("json_object", request.responseFormat.type);
        assertEquals(2, request.messages.size());
        assertEquals("hi", request.messages.getLast().getTextContent());

        // preprocessing mutates messages in place; a second restore must start from the pristine snapshot
        request.messages.getFirst().content.getFirst().text = "mutated";
        request.messages.getFirst().name = "changed";
        snapshot.restore(request);
        assertEquals("sys", request.messages.getFirst().getTextContent());
        assertNull(request.messages.getFirst().name);
    }

    private HTTPClientException statusError(int statusCode) {
        return new HTTPClientException("invalid sse response, statusCode=" + statusCode + ", content-type=text/html", "HTTP_REQUEST_FAILED");
    }
}
