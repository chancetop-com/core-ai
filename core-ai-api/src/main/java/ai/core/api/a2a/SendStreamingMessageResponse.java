package ai.core.api.a2a;

/**
 * JSON-RPC response envelope used for each SSE data frame in SendStreamingMessage.
 *
 * @author xander
 */
public class SendStreamingMessageResponse extends JsonRpcResponse<StreamResponse> {
    public static SendStreamingMessageResponse ofResult(Object id, StreamResponse result) {
        var response = new SendStreamingMessageResponse();
        response.id = id;
        response.result = result;
        return response;
    }

    public static SendStreamingMessageResponse ofError(Object id, JsonRpcError error) {
        var response = new SendStreamingMessageResponse();
        response.id = id;
        response.error = error;
        return response;
    }
}
