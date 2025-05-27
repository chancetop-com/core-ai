package ai.core.litellm;

import ai.core.litellm.completion.CreateCompletionAJAXRequest;
import ai.core.litellm.completion.CreateCompletionAJAXResponse;
import ai.core.litellm.completion.CreateImageCompletionAJAXRequest;
import ai.core.litellm.embedding.CreateEmbeddingAJAXRequest;
import ai.core.litellm.embedding.CreateEmbeddingAJAXResponse;
import ai.core.litellm.embedding.EmbeddingAJAXWebService;
import ai.core.litellm.image.CreateImageAJAXRequest;
import ai.core.litellm.image.CreateImageAJAXResponse;
import ai.core.litellm.image.ImageAJAXWebService;
import ai.core.litellm.model.ModelAJAXWebService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.internal.json.JSONAnnotationIntrospector;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.nio.charset.StandardCharsets;

/**
 * @author stephen
 */
public class LiteLLMService {
    @Inject
    ModelAJAXWebService modelAJAXWebService;
    @Inject
    EmbeddingAJAXWebService embeddingAJAXWebService;
    @Inject
    ImageAJAXWebService imageAJAXWebService;
    private final String url;
    private final String token;
    private final ObjectMapper mapper;

    public LiteLLMService(String url, String token) {
        this.url = url;
        this.token = token;
        this.mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setAnnotationIntrospector(new JSONAnnotationIntrospector());
    }

    public CreateCompletionAJAXResponse completion(CreateCompletionAJAXRequest request) {
        var client = HTTPClient.builder().trustAll().build();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        try {
            var body = mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            req.body(body, ContentType.APPLICATION_JSON);
            if (!Strings.isBlank(token)) {
                req.headers.put("Authorization", "Bearer " + token);
            }
            var rsp = client.execute(req);
            if (rsp.statusCode != 200) {
                throw new RuntimeException(rsp.text());
            }
            return JSON.fromJSON(CreateCompletionAJAXResponse.class, rsp.text());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create completion: " + e.getMessage(), e);
        }
    }

    public CreateCompletionAJAXResponse completion(CreateImageCompletionAJAXRequest request) {
        var client = HTTPClient.builder().trustAll().build();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        try {
            var body = mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            req.body(body, ContentType.APPLICATION_JSON);
            if (!Strings.isBlank(token)) {
                req.headers.put("Authorization", "Bearer " + token);
            }
            var rsp = client.execute(req);
            if (rsp.statusCode != 200) {
                throw new RuntimeException(rsp.text());
            }
            return JSON.fromJSON(CreateCompletionAJAXResponse.class, rsp.text());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create completion: " + e.getMessage(), e);
        }
    }

    public CreateEmbeddingAJAXResponse embedding(CreateEmbeddingAJAXRequest request) {
        return embeddingAJAXWebService.embedding(request);
    }

    public CreateImageAJAXResponse generateImage(CreateImageAJAXRequest request) {
        return imageAJAXWebService.generate(request);
    }
}
