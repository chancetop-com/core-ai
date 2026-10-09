package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class JsonRpcCodecTest {
    @Test
    void parsesRequestWithNumericId() {
        var request = JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/send\",\"params\":{\"sessionId\":\"s1\"}}");
        assertEquals("session/send", request.method());
        assertEquals(7, request.id().asInt());
        assertEquals("s1", request.params().path("sessionId").asText());
        assertFalse(request.notification());
    }

    @Test
    void parsesRequestWithStringId() {
        var request = JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"session/list\"}");
        assertEquals("abc", request.id().asText());
        assertTrue(request.params().isObject());
    }

    @Test
    void missingOrNullIdIsNotification() {
        assertTrue(JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\",\"method\":\"x\"}").notification());
        assertTrue(JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"x\"}").notification());
    }

    @Test
    void rejectsMalformedJson() {
        var error = assertThrows(RpcException.class, () -> JsonRpcCodec.parse("not json"));
        assertEquals(RpcException.PARSE_ERROR, error.code());
    }

    @Test
    void rejectsNonObjectRequest() {
        var error = assertThrows(RpcException.class, () -> JsonRpcCodec.parse("[1,2]"));
        assertEquals(RpcException.INVALID_REQUEST, error.code());
    }

    @Test
    void rejectsWrongJsonrpcVersion() {
        var error = assertThrows(RpcException.class, () -> JsonRpcCodec.parse("{\"jsonrpc\":\"1.0\",\"method\":\"x\"}"));
        assertEquals(RpcException.INVALID_REQUEST, error.code());
    }

    @Test
    void rejectsMissingMethod() {
        var error = assertThrows(RpcException.class, () -> JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1}"));
        assertEquals(RpcException.INVALID_REQUEST, error.code());
    }

    @Test
    void rejectsNonObjectParams() {
        var error = assertThrows(RpcException.class,
                () -> JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\",\"params\":[1]}"));
        assertEquals(RpcException.INVALID_REQUEST, error.code());
    }

    @Test
    void buildsResponseErrorAndNotification() {
        var id = JsonUtil.OBJECT_MAPPER.getNodeFactory().numberNode(9);
        var result = JsonUtil.OBJECT_MAPPER.createObjectNode().put("ok", true);
        var response = JsonRpcCodec.response(id, result);
        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(9, response.path("id").asInt());
        assertTrue(response.path("result").path("ok").asBoolean());

        var error = JsonRpcCodec.error(id, RpcException.INTERNAL_ERROR, "boom", null);
        assertEquals(RpcException.INTERNAL_ERROR, error.path("error").path("code").asInt());
        assertEquals("boom", error.path("error").path("message").asText());
        assertTrue(error.path("error").path("data").isMissingNode());

        var notification = JsonRpcCodec.notification("session/event", result);
        assertNull(notification.get("id"));
        assertEquals("session/event", notification.path("method").asText());

        ObjectNode parsed = JsonRpcCodec.emptyParams();
        assertTrue(parsed.isObject());
    }

    @Test
    void businessExceptionCarriesDataCode() {
        var error = RpcException.business("SESSION_NOT_FOUND", "no such session", "sessionId", "s9");
        assertEquals("SESSION_NOT_FOUND", error.data().path("code").asText());
        assertEquals("s9", error.data().path("sessionId").asText());
        assertEquals(RpcException.INTERNAL_ERROR, error.code());
    }
}
