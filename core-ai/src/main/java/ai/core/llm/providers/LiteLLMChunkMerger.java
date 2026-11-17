package ai.core.llm.providers;

import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.streaming.StreamingCallback;

import java.util.ArrayList;

/**
 * Merges chat/completions streaming chunks into the final response: content and reasoning are
 * accumulated as builders, tool calls are stitched together by index.
 *
 * @author stephen
 */
final class LiteLLMChunkMerger {

    static void initializeFinalChoiceMessage(CompletionResponse finalResponse) {
        if (finalResponse.choices == null || finalResponse.choices.isEmpty()) {
            return;
        }

        var finalChoice = finalResponse.choices.getFirst();
        if (finalChoice.delta == null) {
            return;
        }

        finalChoice.message = new AssistantMessage();
        finalChoice.message.role = finalChoice.delta.role;
        finalChoice.message.content = "";
        finalChoice.message.reasoningContent = "";
        finalChoice.message.toolCalls = new ArrayList<>();
    }

    static void mergeChunkIntoFinalResponse(CompletionResponse finalResponse, CompletionResponse chunk, StreamingCallback streamingCallback) {
        if (chunk.choices != null && finalResponse.choices != null && !chunk.choices.isEmpty() && !finalResponse.choices.isEmpty()) {
            var finalChoice = finalResponse.choices.getFirst();
            var chunkChoice = chunk.choices.getFirst();

            if (chunkChoice.delta != null) {
                copyDeltaToFinalChoice(finalChoice, chunkChoice, streamingCallback);
            }

            // Update finish reason from chunk
            if (chunkChoice.finishReason != null) {
                finalChoice.finishReason = chunkChoice.finishReason;
            }
        }

        // Update usage from chunk
        if (chunk.usage != null) {
            finalResponse.usage = chunk.usage;
        }
    }

    private static void copyDeltaToFinalChoice(Choice finalChoice, Choice chunkChoice, StreamingCallback streamingCallback) {
        // Ensure message exists
        if (finalChoice.message == null) {
            finalChoice.message = new AssistantMessage();
            finalChoice.message.content = "";
            finalChoice.message.reasoningContent = "";
            finalChoice.message.toolCalls = new ArrayList<>();
        }

        // Merge content into message using StringBuilder to avoid O(n^2) string allocations
        if (chunkChoice.delta.content != null) {
            finalChoice.message.appendContent(chunkChoice.delta.content);
        }
        if (chunkChoice.delta.reasoningContent != null) {
            finalChoice.message.appendReasoningContent(chunkChoice.delta.reasoningContent);
        }

        // Merge tool calls into message by index
        if (chunkChoice.delta.toolCalls != null) {
            streamingCallback.onTool(chunkChoice.delta.toolCalls);
            copyToolCallsToFinalChoice(finalChoice, chunkChoice);
        }

        // Merge role if not set
        if (chunkChoice.delta.role != null && finalChoice.message.role == null) {
            finalChoice.message.role = chunkChoice.delta.role;
        }
    }

    // package-private so tests can exercise the merge directly
    static void copyToolCallsToFinalChoice(Choice finalChoice, Choice chunkChoice) {
        if (finalChoice.message.toolCalls == null) {
            finalChoice.message.toolCalls = new ArrayList<>();
        }

        for (var deltaToolCall : chunkChoice.delta.toolCalls) {
            if (deltaToolCall.index == null) {
                continue;
            }
            while (finalChoice.message.toolCalls.size() <= deltaToolCall.index) {
                finalChoice.message.toolCalls.add(null);
            }

            var existingToolCall = finalChoice.message.toolCalls.get(deltaToolCall.index);
            if (existingToolCall == null) {
                existingToolCall = new FunctionCall();
                existingToolCall.function = new FunctionCall.Function();
                existingToolCall.function.arguments = "";
                finalChoice.message.toolCalls.set(deltaToolCall.index, existingToolCall);
            }

            if (deltaToolCall.id != null) {
                existingToolCall.id = deltaToolCall.id;
            }
            if (deltaToolCall.type != null) {
                existingToolCall.type = deltaToolCall.type;
            }
            if (deltaToolCall.extraContent != null) {
                existingToolCall.extraContent = deltaToolCall.extraContent;
            }
            if (deltaToolCall.function != null) {
                if (deltaToolCall.function.name != null) {
                    existingToolCall.function.name = deltaToolCall.function.name;
                }
                if (deltaToolCall.function.arguments != null) {
                    existingToolCall.function.appendArguments(deltaToolCall.function.arguments);
                }
            }
        }
    }

    private LiteLLMChunkMerger() {
    }
}
