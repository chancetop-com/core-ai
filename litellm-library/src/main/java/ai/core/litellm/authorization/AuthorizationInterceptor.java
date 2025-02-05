package ai.core.litellm.authorization;

import ai.core.litellm.completion.CreateImageCompletionAJAXRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.ContentType;
import core.framework.http.HTTPRequest;
import core.framework.internal.json.JSONAnnotationIntrospector;
import core.framework.json.JSON;
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
        var body = new String(request.body, StandardCharsets.UTF_8);
        // tricky part
        if (request.uri.contains("/chat/completions") && body.contains("__image_completion_flag__")) {
            var data = JSON.fromJSON(CreateImageCompletionAJAXRequest.class, body);
            try {
                request.body(mapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        request.headers.put("Authorization", "Basic " + token);
    }
}
