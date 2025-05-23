package ai.core.litellm.authorization;

import ai.core.litellm.completion.CreateCompletionAJAXRequest;
import ai.core.litellm.completion.CreateImageCompletionAJAXRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.ContentType;
import core.framework.http.HTTPRequest;
import core.framework.internal.json.JSONAnnotationIntrospector;
import core.framework.json.JSON;
import core.framework.util.Strings;
import core.framework.web.service.WebServiceClientInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * @author stephen
 */
public class AuthorizationInterceptor implements WebServiceClientInterceptor {
    private final String token;
    private final ObjectMapper mapper;

    public AuthorizationInterceptor(String token) {
        this.token = token;
        this.mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setAnnotationIntrospector(new JSONAnnotationIntrospector());
    }

    @Override
    public void onRequest(HTTPRequest request) {
        // tricky part
        if (request.uri.contains("/chat/completions")) {
            var body = new String(request.body, StandardCharsets.UTF_8);
            byte[] data;
            try {
                if (body.contains("__image_completion_flag__")) {
                    var j = JSON.fromJSON(CreateImageCompletionAJAXRequest.class, body);
                    data = mapper.writeValueAsString(j).getBytes(StandardCharsets.UTF_8);
                } else {
                    var j = JSON.fromJSON(CreateCompletionAJAXRequest.class, body);
                    data = mapper.writeValueAsString(j).getBytes(StandardCharsets.UTF_8);
                }
                request.body(data, ContentType.APPLICATION_JSON);
            } catch (Exception e) {
                throw new RuntimeException("failed to parse request body", e);
            }
        }
        if (!Strings.isBlank(token)) {
            request.headers.put("Authorization", "Bearer " + token);
        }
    }
}
