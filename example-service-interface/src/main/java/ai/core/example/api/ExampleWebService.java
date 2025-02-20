package ai.core.example.api;

import ai.core.example.api.example.MCPToolCallRequest;
import ai.core.example.api.example.OrderIssueResponse;
import ai.core.example.api.example.UserInputRequest;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface ExampleWebService {
    @PUT
    @Path("/example/agent")
    ChatResponse agent(ChatRequest request);

    @PUT
    @Path("/example/group-start")
    OrderIssueResponse groupStart(ChatRequest request);

    @PUT
    @Path("/example/group-finish")
    OrderIssueResponse groupFinish(UserInputRequest request);

    @PUT
    @Path("/example/user-input-start")
    ChatResponse userInputStart(ChatRequest request);

    @PUT
    @Path("/example/user-input-finish")
    ChatResponse userInputFinish(UserInputRequest request);

    @PUT
    @Path("/example/thinking")
    ChatResponse thinking(ChatRequest request);

    @PUT
    @Path("/example/chain")
    ChatResponse chain(ChatRequest request);

    @PUT
    @Path("/example/summary")
    ChatResponse summary(ChatRequest request);

    @PUT
    @Path("/example/function")
    ChatResponse function(ChatRequest request);

    @PUT
    @Path("/example/mcp/git")
    ChatResponse git(MCPToolCallRequest request);
}
