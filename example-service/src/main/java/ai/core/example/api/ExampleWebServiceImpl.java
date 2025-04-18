package ai.core.example.api;

import ai.core.example.api.example.MCPToolCallRequest;
import ai.core.example.api.example.OrderIssueResponse;
import ai.core.example.api.example.UserInputRequest;
import ai.core.example.service.ExampleService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class ExampleWebServiceImpl implements ExampleWebService {
    @Inject
    ExampleService exampleService;

    @Override
    public ChatResponse flow(ChatRequest request) {
        return toRsp(exampleService.flow(request.query));
    }

    @Override
    public ChatResponse agent(ChatRequest request) {
        return toRsp(exampleService.optimize(request.query));
    }

    @Override
    public OrderIssueResponse groupStart(ChatRequest request) {
        return exampleService.groupStart(request.query);
    }

    @Override
    public OrderIssueResponse groupFinish(UserInputRequest request) {
        return exampleService.groupFinish(request.id, request.query);
    }

    @Override
    public ChatResponse userInputStart(ChatRequest request) {
        return toRsp(exampleService.userInputStart());
    }

    @Override
    public ChatResponse userInputFinish(UserInputRequest request) {
        return toRsp(exampleService.userInputFinish(request.id, request.query));
    }

    @Override
    public ChatResponse thinking(ChatRequest request) {
        return toRsp(exampleService.chat(request.query));
    }

    @Override
    public ChatResponse chain(ChatRequest request) {
        return toRsp(exampleService.debate(request.query));
    }

    @Override
    public ChatResponse summary(ChatRequest request) {
        return toRsp(exampleService.summaryOptimize(request.query));
    }

    @Override
    public ChatResponse function(ChatRequest request) {
        return toRsp(exampleService.function(request.query));
    }

    @Override
    public ChatResponse git(MCPToolCallRequest request) {
        return toRsp(exampleService.mcpToolCallTest(request));
    }

    private ChatResponse toRsp(String text) {
        var rsp = new ChatResponse();
        rsp.text = text;
        return rsp;
    }
}
