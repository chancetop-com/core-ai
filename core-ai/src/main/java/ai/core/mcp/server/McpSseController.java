package ai.core.mcp.server;

import core.framework.http.ContentType;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class McpSseController implements Controller {
    @Override
    public Response execute(Request request) throws Exception {
        var rsp = Response.bytes("event: endpoint\ndata: /mcp/sse\n\n".getBytes());
        rsp.contentType(ContentType.parse("text/event-stream; charset=utf-8"));
        return rsp;
    }
}
