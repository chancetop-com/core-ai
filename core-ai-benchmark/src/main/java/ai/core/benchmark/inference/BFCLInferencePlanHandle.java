package ai.core.benchmark.inference;

import ai.core.benchmark.domain.BFCLItemEvalResult;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.Tool;
import ai.core.tool.tools.WriteTodosTool;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/1/9
 * description: base write todos
 */
public class BFCLInferencePlanHandle extends BFCLInferenceFCHandle{
    public BFCLInferencePlanHandle(LLMProvider llmProvider) {
        super(llmProvider);
    }

    @Override
    protected BFCLItemEvalResult invoke(String id, List<Message> messages, List<Tool> tools) {
        var request = CompletionRequest.of(messages, tools, llmProvider.config.getTemperature(), llmProvider.config.getModel(), "eval-req");
        return null;
    }

    private void wrapSystemMsg(List<Message> messages){

    }

    private void wrapTools(List<Tool> tools){
        tools.add(WriteTodosTool.self().toTool());
    }

}
