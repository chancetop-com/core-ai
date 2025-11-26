package ai.core.llm.providers;

import ai.core.llm.LLMProviderConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.StructuredResponseCreateParams;

import java.util.List;

/**
 * author: lim chen
 * date: 2025/11/21
 * description:
 */
//todo  Refactoring
public class OpenAIProvider {
    private final OpenAIClient client;
    private final LLMProviderConfig config;

    public OpenAIProvider(LLMProviderConfig config, String apiKey, String baseUrl) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(config.getTimeout())
                .build();
        this.config = config;
    }

    public <T> T responsesApi(List<String> msg, Class<T> responseFormat, String userId, String modelName) {
        StructuredResponseCreateParams<T> createParams = ResponseCreateParams.builder()
                .instructions(msg.getFirst())
                .input(msg.getLast())
                .text(responseFormat)
                .model(modelName)
                .temperature(config.getTemperature())
                .user(userId)
                .build();

        return client.responses().create(createParams).output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .toList().getFirst();

    }

    public <T> T responseApiWithImageUrl(String inputText, String imgPath, Class<T> responseFormat, String userId, String modelName) {
        ResponseInputImage logoInputImage = ResponseInputImage.builder()
                .detail(ResponseInputImage.Detail.AUTO)
                .imageUrl(imgPath)
                .build();
        ResponseInputItem userInput = ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
                .role(ResponseInputItem.Message.Role.USER)
                .addInputTextContent(inputText)
                .addContent(logoInputImage)
                .build());

        StructuredResponseCreateParams<T> createParams = ResponseCreateParams.builder()
                .text(responseFormat)
                .model(modelName)
                .temperature(config.getTemperature())
                .inputOfResponse(List.of(userInput))
                .user(userId)
                .build();

        return client.responses().create(createParams).output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .toList().getFirst();
    }
}
