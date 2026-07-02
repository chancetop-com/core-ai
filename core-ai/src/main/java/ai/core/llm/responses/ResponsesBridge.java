package ai.core.llm.responses;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.llm.domain.responses.ResponsesResponse;
import ai.core.llm.streaming.StreamingCallback;

public class ResponsesBridge {
    private final LLMProvider provider;
    private final ResponsesRequestMapper requestMapper;

    public ResponsesBridge(LLMProvider provider) {
        this(provider, new ResponsesRequestMapper());
    }

    ResponsesBridge(LLMProvider provider, ResponsesRequestMapper requestMapper) {
        this.provider = provider;
        this.requestMapper = requestMapper;
    }

    public ResponsesResponse stream(ResponsesRequest request, ResponsesEventListener listener) {
        var completionRequest = requestMapper.map(request);
        var synthesizer = new ResponsesStreamSynthesizer(request);
        synthesizer.start(listener);
        try {
            var completion = provider.completionStream(completionRequest, new StreamingCallback() {
                @Override
                public void onChunk(String chunk) {
                }

                @Override
                public void onRawData(String sseData) {
                    synthesizer.accept(sseData, listener);
                }
            }, null, false);
            return synthesizer.complete(completion, listener);
        } catch (RuntimeException e) {
            return synthesizer.fail(e, listener);
        }
    }
}
