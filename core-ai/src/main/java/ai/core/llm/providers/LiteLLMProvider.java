package ai.core.llm.providers;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.nio.charset.StandardCharsets;

/**
 * @author stephen
 */
public class LiteLLMProvider extends LLMProvider {
    private final String url;
    private final String token;

    public LiteLLMProvider(LLMProviderConfig config, String url, String token) {
        super(config);
        this.url = url;
        this.token = token;
    }

    @Override
    public CompletionResponse completion(CompletionRequest dto) {
        dto.temperature = dto.temperature != null ? dto.temperature : config.getTemperature();
        if (dto.model.startsWith("o1") || dto.model.startsWith("o3")) {
            dto.temperature = null;
        }
        dto.messages.forEach(message -> {
            if (message.role == RoleType.SYSTEM && dto.model.startsWith("o1")) {
                message.role = RoleType.USER;
            }
            if (message.role == RoleType.ASSISTANT && message.name == null) {
                message.name = "assistant";
            }
        });
        return chatCompletion(dto);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest dto) {
        return null;
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest dto) {
        return null;
    }

    @Override
    public int maxTokens() {
        return 128 * 1000;
    }

    @Override
    public String name() {
        return "litellm";
    }

    public CompletionResponse chatCompletion(CompletionRequest request) {
        var client = HTTPClient.builder().trustAll().build();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        try {
            var body = JsonUtil.toJson(request).getBytes(StandardCharsets.UTF_8);
            req.body(body, ContentType.APPLICATION_JSON);
            if (!Strings.isBlank(token)) {
                req.headers.put("Authorization", "Bearer " + token);
            }
            var rsp = client.execute(req);
            if (rsp.statusCode != 200) {
                throw new RuntimeException(rsp.text());
            }
            var rst = JSON.fromJSON(CompletionResponse.class, rsp.text());
            rst.choices.forEach(v -> {
                if (v.message.content == null) {
                    v.message.content = "";
                }
            });
            return rst;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create completion: " + e.getMessage(), e);
        }
    }
}
