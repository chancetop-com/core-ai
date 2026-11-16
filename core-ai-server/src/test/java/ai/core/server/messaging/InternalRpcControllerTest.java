package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class InternalRpcControllerTest {
    private final InProcessCommandHandler handler = mock(InProcessCommandHandler.class);

    @Test
    void rejectsMissingToken() {
        var controller = controller("secret", null);

        assertEquals(HTTPStatus.UNAUTHORIZED, controller.handle(request(null, "pod-1", body())).status());
        verify(handler, never()).handleRpc(any());
    }

    @Test
    void rejectsWrongToken() {
        var controller = controller("secret", null);

        assertEquals(HTTPStatus.UNAUTHORIZED, controller.handle(request("wrong", "pod-1", body())).status());
    }

    @Test
    void acceptsPreviousTokenDuringRotation() {
        var controller = controller("secret", "old");
        when(handler.handleRpc(any())).thenReturn(RpcClient.okResponse("{}"));

        assertEquals(HTTPStatus.OK, controller.handle(request("old", "pod-1", body())).status());
    }

    @Test
    void rejectsMissingTargetPod() {
        var controller = controller("secret", null);

        assertEquals(HTTPStatus.BAD_REQUEST, controller.handle(request("secret", "", body())).status());
    }

    @Test
    void rejectsWrongTargetPod() {
        var controller = controller("secret", null);

        assertEquals(HTTPStatus.CONFLICT, controller.handle(request("secret", "pod-2", body())).status());
    }

    @Test
    void rejectsNonRpcCommand() {
        var controller = controller("secret", null);
        var body = JsonUtil.toJson(new InternalRpcCommand("SEND_MESSAGE", "s1", "u1", "{}", "r1")).getBytes(StandardCharsets.UTF_8);

        assertEquals(HTTPStatus.BAD_REQUEST, controller.handle(request("secret", "pod-1", body)).status());
    }

    @Test
    void rejectsMissingRequestId() {
        var controller = controller("secret", null);
        var body = JsonUtil.toJson(new InternalRpcCommand("LOAD_SKILLS", "s1", "u1", "{}", "")).getBytes(StandardCharsets.UTF_8);

        assertEquals(HTTPStatus.BAD_REQUEST, controller.handle(request("secret", "pod-1", body)).status());
    }

    @Test
    void executesRpcAndReturnsResponse() {
        var controller = controller("secret", null);
        when(handler.handleRpc(any())).thenReturn(RpcClient.okResponse("{\"ok\":true}"));
        var captor = ArgumentCaptor.forClass(SessionCommand.class);

        var response = controller.handle(request("secret", "pod-1", body()));

        assertEquals(HTTPStatus.OK, response.status());
        verify(handler).handleRpc(captor.capture());
        assertEquals(CommandType.LOAD_SKILLS, captor.getValue().type());
        assertEquals("s1", captor.getValue().sessionId());
        assertEquals("r1", captor.getValue().requestId());
    }

    @Test
    void handlerFailureBecomes500Never404() {
        var controller = controller("secret", null);
        when(handler.handleRpc(any())).thenThrow(new IllegalStateException("boom"));

        assertEquals(HTTPStatus.INTERNAL_SERVER_ERROR, controller.handle(request("secret", "pod-1", body())).status());
    }

    private InternalRpcController controller(String token, String previousToken) {
        var podRegistry = mock(PodRegistry.class);
        when(podRegistry.podId()).thenReturn("pod-1");
        return new InternalRpcController(handler, podRegistry, token, previousToken);
    }

    private Request request(String token, String targetPod, byte[] body) {
        var request = mock(Request.class);
        when(request.header("X-Internal-Token")).thenReturn(Optional.ofNullable(token));
        when(request.header("X-Target-Pod")).thenReturn(Optional.ofNullable(targetPod));
        when(request.body()).thenReturn(Optional.of(body));
        return request;
    }

    private byte[] body() {
        return JsonUtil.toJson(new InternalRpcCommand("LOAD_SKILLS", "s1", "u1", "{}", "r1"))
            .getBytes(StandardCharsets.UTF_8);
    }
}
