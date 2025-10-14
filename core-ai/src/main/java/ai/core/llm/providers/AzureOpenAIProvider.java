package ai.core.llm.providers;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.inner.AzureOpenAIModelsUtil;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.OpenAIServiceVersion;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.KeyCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class AzureOpenAIProvider extends LLMProvider {
    private final OpenAIClient chatClient;
    private final OpenAIAsyncClient chatAsyncClient;

    public AzureOpenAIProvider(LLMProviderConfig config, String apiKey, String endpoint) {
        this(config, apiKey, endpoint, OpenAIServiceVersion.getLatest());
    }

    public AzureOpenAIProvider(LLMProviderConfig config, String apiKey, String endpoint, OpenAIServiceVersion serviceVersion) {
        super(config);
        var options = new HttpClientOptions();
        options.setConnectTimeout(config.getConnectTimeout());
        options.setReadTimeout(config.getTimeout());
        options.setResponseTimeout(config.getTimeout());
        options.setConnectionIdleTimeout(Duration.ofMinutes(5));
        OpenAIClientBuilder builder;
        if (endpoint == null) {
            // openai api key without endpoint
            builder = new OpenAIClientBuilder().httpClient(HttpClient.createDefault(options)).credential(new KeyCredential(apiKey)).serviceVersion(serviceVersion);
        } else {
            builder = new OpenAIClientBuilder().httpClient(HttpClient.createDefault(options)).credential(new AzureKeyCredential(apiKey)).endpoint(endpoint).serviceVersion(serviceVersion);
        }
        this.chatClient = builder.buildClient();
        this.chatAsyncClient = builder.buildAsyncClient();
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        var chatCompletion = chatClient.getChatCompletions(request.model, AzureOpenAIModelsUtil.toAzureRequest(request));
        return CompletionResponse.of(AzureOpenAIModelsUtil.toChoice(chatCompletion.getChoices(), request.getName()), AzureOpenAIModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        var stream = chatAsyncClient.getChatCompletionsStream(request.model, AzureOpenAIModelsUtil.toAzureRequest(request));
        var choices = new ArrayList<Choice>();
        var usage = new AtomicReference<>(new Usage(0, 0, 0));
        var latch = new CountDownLatch(1);
        var contentBuilder = new StringBuilder();
        var finishReason = new AtomicReference<String>();
        var role = new AtomicReference<RoleType>();
        var toolCalls = new ArrayList<FunctionCall>();

        stream.subscribe(
                completion -> handleDelta(completion, contentBuilder, finishReason, toolCalls, callback),
                error -> {
                    callback.onError(error);
                    latch.countDown();
                },
                () -> {
                    callback.onComplete();
                    // Remove null entries from toolCalls
                    toolCalls.removeIf(Objects::isNull);

                    // Build complete message
                    var message = new Message();
                    message.role = role.get() != null ? role.get() : RoleType.ASSISTANT;
                    message.content = contentBuilder.toString();
                    message.toolCalls = toolCalls.isEmpty() ? null : toolCalls;
                    message.name = request.getName();

                    // Build complete choice
                    var completeChoice = new Choice();
                    completeChoice.message = message;
                    completeChoice.finishReason = finishReason.get() != null ? FinishReason.valueOf(finishReason.get().toUpperCase(Locale.ROOT)) : FinishReason.STOP;

                    choices.add(completeChoice);
                    latch.countDown();
                }
        );

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onError(e);
        }

        return CompletionResponse.of(choices, usage.get());
    }

    private void handleDelta(ChatCompletions completion, StringBuilder contentBuilder, AtomicReference<String> finishReason, List<FunctionCall> toolCalls, StreamingCallback callback) {
        if (completion.getChoices() == null || completion.getChoices().isEmpty()) {
            return; // No choices in this chunk, skip processing
        }

        var choice = completion.getChoices().getFirst();

        // Capture finish reason
        if (choice.getFinishReason() != null) {
            finishReason.set(choice.getFinishReason().toString());
        }

        if (choice.getDelta() == null) return;

        // Accumulate content
        if (choice.getDelta().getContent() != null) {
            var content = choice.getDelta().getContent();
            contentBuilder.append(content);
            callback.onChunk(content);
        }

        // Merge tool calls by array index
        if (choice.getDelta().getToolCalls() != null) {
            mergeToolCalls(choice, toolCalls);
        }
    }

    private void mergeToolCalls(ChatChoice choice, List<FunctionCall> toolCalls) {
        var deltaToolCalls = choice.getDelta().getToolCalls();
        for (int i = 0; i < deltaToolCalls.size(); i++) {
            var deltaToolCall = deltaToolCalls.get(i);

            while (toolCalls.size() <= i) {
                toolCalls.add(null);
            }

            var existingToolCall = toolCalls.get(i);
            if (existingToolCall == null) {
                addNewToolCall(i, deltaToolCall, toolCalls);
            } else {
                mergeDeltaToolCall(existingToolCall, deltaToolCall);
            }
        }
    }

    private void addNewToolCall(int i, ChatCompletionsToolCall deltaToolCall, List<FunctionCall> toolCalls) {
        var existingToolCall = new FunctionCall();
        existingToolCall.id = deltaToolCall.getId();
        existingToolCall.type = deltaToolCall.getType();
        existingToolCall.function = new FunctionCall.Function();
        if (deltaToolCall instanceof ChatCompletionsFunctionToolCall fCall && fCall.getFunction() != null) {
            existingToolCall.function.name = fCall.getFunction().getName();
            existingToolCall.function.arguments = fCall.getFunction().getArguments() != null ? fCall.getFunction().getArguments() : "";
        } else {
            existingToolCall.function.name = "";
            existingToolCall.function.arguments = "";
        }
        toolCalls.set(i, existingToolCall);
    }

    private void mergeDeltaToolCall(FunctionCall existingToolCall, ChatCompletionsToolCall deltaToolCall) {
        if (existingToolCall.function.arguments == null) {
            existingToolCall.function.arguments = "";
        }
        if (deltaToolCall instanceof ChatCompletionsFunctionToolCall fCall && fCall.getFunction() != null) {
            if (fCall.getFunction().getArguments() != null) {
                existingToolCall.function.arguments += fCall.getFunction().getArguments();
            }
            if (fCall.getFunction().getName() != null) {
                existingToolCall.function.name = fCall.getFunction().getName();
            }
        }
        if (deltaToolCall.getId() != null && !deltaToolCall.getId().isEmpty()) {
            existingToolCall.id = deltaToolCall.getId();
        }
        if (deltaToolCall.getType() != null) {
            existingToolCall.type = deltaToolCall.getType();
        }
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        var embeddingsOptions = new EmbeddingsOptions(request.query());
        var embeddings = chatClient.getEmbeddings(config.getEmbeddingModel(), embeddingsOptions);
        return AzureOpenAIModelsUtil.toEmbeddingResponse(request, embeddings);
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) {
        return null;
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        var chatCompletion = chatClient.getChatCompletions(request.model(), AzureOpenAIModelsUtil.toAzureRequest(request));
        return new CaptionImageResponse(chatCompletion.getChoices().getFirst().getMessage().getContent(), AzureOpenAIModelsUtil.toUsage(chatCompletion.getUsage()));
    }

    @Override
    public int maxTokens() {
        return 128 * 1000;
    }

    @Override
    public String name() {
        return "azure";
    }
}
