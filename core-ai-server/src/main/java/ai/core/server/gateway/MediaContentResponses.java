package ai.core.server.gateway;

import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.web.Response;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * @author Stephen
 */
final class MediaContentResponses {
    static Response error(HTTPStatus status, String errorCode, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("errorCode", errorCode);
        body.put("message", message == null ? errorCode : message);
        return Response.bytes(JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8))
                .status(status)
                .contentType(ContentType.APPLICATION_JSON);
    }

    private MediaContentResponses() {
    }
}
