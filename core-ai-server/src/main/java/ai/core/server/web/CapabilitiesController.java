package ai.core.server.web;

import ai.core.api.a2a.A2ACapabilities;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class CapabilitiesController {
    public boolean authDisabled;

    public Response get(Request request) {
        var caps = A2ACapabilities.serverMode();
        if (authDisabled) {
            caps.authRequired = false;
        }
        var json = JsonUtil.toJson(caps).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Response.bytes(json).contentType(ContentType.APPLICATION_JSON);
    }
}
