package ai.core.benchmark.inference;

import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.prompt.Prompts;
import ai.core.tool.tools.WriteTodosTool;
import core.framework.util.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * author: lim chen
 * date: 2026/1/9
 * description: base write todos
 */
public class BFCLInferencePlanHandle extends BFCLInferenceFCHandle {
    private static final Logger LOGGER = LoggerFactory.getLogger(BFCLInferencePlanHandle.class);

    public BFCLInferencePlanHandle(LLMProvider llmProvider) {
        super(llmProvider);
    }

    @Override
    protected BFCLItemEvalResult invoke(String id, List<Message> messages, List<Tool> tools) {
        var curMsg = wrapSystemMsg(messages);
        var curTools = wrapTools(tools);
        var request = CompletionRequest.of(curMsg, curTools, llmProvider.config.getTemperature(), llmProvider.config.getModel(), "eval-req");
        return loop(id, request);
    }

    private BFCLItemEvalResult loop(String id, CompletionRequest request) {
        List<Map<String, Object>> result;
        int inputTokenCount = 0;
        int outputTokenCount = 0;
        double latency = 0.0;
        var step = 0;
        boolean isCallLocalMethod;

        do {
            step++;
            var completionResponseWithLatency = invokeLLM(request);
            CompletionResponse rawRes = completionResponseWithLatency.response;
            inputTokenCount += rawRes.usage.getPromptTokens();
            outputTokenCount += rawRes.usage.getCompletionTokens();
            latency += completionResponseWithLatency.latency;
            var clResult = handleToolCall(rawRes);
            var msg = clResult.message;
            isCallLocalMethod = clResult.isWriteTodos;
            result = clResult.result;
            List<Message> newMsg = Lists.newArrayList();
            newMsg.addAll(request.messages);
            newMsg.add(rawRes.choices.getFirst().message.toMessage());
            newMsg.add(msg);
            request.messages = newMsg;
        } while (step < 4 && isCallLocalMethod);

        return BFCLItemEvalResult.of(id, result, inputTokenCount, outputTokenCount, latency);
    }


    private CallWT handleToolCall(CompletionResponse rawRes) {
        List<Map<String, Object>> result = Lists.newArrayList();
        if (rawRes.choices.getFirst().finishReason == FinishReason.TOOL_CALLS) {
            var tools = rawRes.choices.getFirst().message.toolCalls;
            if (isWriteTodos(tools)) {
                LOGGER.info("handle  write todos ...");
                return new CallWT(true, handleWriteTodos(tools), null);
            }
            tools.stream().map(tool -> Map.of(tool.function.name, (Object) tool.function.arguments)).forEach(result::add);
        }
        if (result.isEmpty()) {
            result.add(Map.of("output", rawRes.choices.getFirst().message.content));
        }
        return new CallWT(false, null, result);
    }

    private boolean isWriteTodos(List<FunctionCall> tools) {
        return tools.stream().anyMatch(tool -> tool.function.name.equals(WriteTodosTool.self().getName()));
    }

    private Message handleWriteTodos(List<FunctionCall> tools) {
        var tool = tools.getFirst();
        var result = WriteTodosTool.self().execute(tool.function.arguments).toResultForLLM();
        return Message.of(RoleType.TOOL, result, null, tool.id, null);
    }

    private CompletionResponseWithLatency invokeLLM(CompletionRequest request) {
        long startTime = System.currentTimeMillis();
        var completionResponse = llmProvider.completion(request);
        long endTime = System.currentTimeMillis();
        double latency = (endTime - startTime) / 1000.0;
        return CompletionResponseWithLatency.of(completionResponse, latency);
    }

    private List<Message> wrapSystemMsg(List<Message> messages) {

        var systemPrompt = """
                you are helpful assistant,
                # Task Management
                Use the `write_todos` tool to plan your tasks step by step.
                %s
                """.formatted(Prompts.WRITE_TODOS_SYSTEM_PROMPT);

        return List.of(Message.of(RoleType.SYSTEM, systemPrompt), messages.getFirst());
    }

    private List<Tool> wrapTools(List<Tool> tools) {
        List<Tool> tempList = Lists.newArrayList();
        tempList.addAll(tools);
        tempList.add(WriteTodosTool.self().toTool());
        return tempList;
    }

    private record CallWT(boolean isWriteTodos, Message message, List<Map<String, Object>> result) {
    }

}
