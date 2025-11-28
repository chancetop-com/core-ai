package ai.core.memory.compression;

import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LLM-based compression strategy that uses language models to summarize content.
 *
 * @author Xander
 */
public class LLMCompressionStrategy implements CompressionStrategy {
    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCompressionStrategy.class);

    private static final String INITIAL_COMPRESSION_PROMPT =
        "Summarize the following conversation concisely, retaining key information for future context:\n\n";

    private static final String INCREMENTAL_COMPRESSION_PROMPT_TEMPLATE =
        "Update the following summary with new conversation content.\n\n"
            + "Current Summary:\n%s\n\n"
            + "New Content:\n%s\n\n"
            + "Provide an updated concise summary:";

    private final LLMProvider llmProvider;

    public LLMCompressionStrategy(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public String compress(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return callLLM(INITIAL_COMPRESSION_PROMPT + content);
    }

    @Override
    public String compressIncremental(String existingSummary, String newContent) {
        if (newContent == null || newContent.isBlank()) {
            return existingSummary != null ? existingSummary : "";
        }
        if (existingSummary == null || existingSummary.isBlank()) {
            return compress(newContent);
        }
        String prompt = String.format(INCREMENTAL_COMPRESSION_PROMPT_TEMPLATE, existingSummary, newContent);
        return callLLM(prompt);
    }

    private String callLLM(String prompt) {
        try {
            var request = CompletionRequest.of(
                List.of(Message.of(RoleType.USER, prompt)),
                null, null, null, null
            );
            var response = llmProvider.completion(request);
            return response.choices.getFirst().message.content;
        } catch (Exception e) {
            LOGGER.warn("LLM compression failed, returning empty result", e);
            return "";
        }
    }
}
