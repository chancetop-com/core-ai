package ai.core.llm.providers;

import ai.core.llm.LLMProviderConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
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

    public OpenAIProvider(LLMProviderConfig config, String apiKey, String baseUrl) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(config.getTimeout())
                .build();
    }

    public <T> T responsesApi(List<String> msg, Class<T> responseFormat, String userId, ChatModel chatModel) {
        StructuredResponseCreateParams<T> createParams = ResponseCreateParams.builder()
                .instructions(msg.getFirst())
                .input(msg.getLast())
                .text(responseFormat)
                .model(chatModel)
                .user(userId)
                .build();

        return client.responses().create(createParams).output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .toList().getFirst();

    }

    public <T> T responseApiWithImageUrl(String inputText, String imgPath, Class<T> responseFormat, String userId, ChatModel chatModel) {
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
                .model(chatModel)
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
