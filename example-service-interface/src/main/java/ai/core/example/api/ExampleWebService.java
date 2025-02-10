package ai.core.example.api;

import ai.core.example.api.naixt.MCPToolCallRequest;
import ai.core.example.api.socialmedia.CotResponse;
import ai.core.example.api.socialmedia.CreateSocialMediaIdeasResponse;
import ai.core.example.api.socialmedia.CreateSocialMediaRequest;
import ai.core.example.api.socialmedia.CreateSocialMediaResponse;
import ai.core.example.api.socialmedia.CreateUserSocialMediaRequest;
import ai.core.example.api.socialmedia.CreateUserSocialMediaResponse;
import ai.core.example.api.socialmedia.FaceIdImageRequest;
import ai.core.example.api.socialmedia.FaceIdImageResponse;
import ai.core.example.api.socialmedia.FluxFillingImageRequest;
import ai.core.example.api.socialmedia.IpAdapterImageRequest;
import ai.core.example.api.socialmedia.OrderIssueResponse;
import ai.core.example.api.socialmedia.RelightImageRequest;
import ai.core.example.api.socialmedia.RelightWithBackgroundImageRequest;
import ai.core.example.api.socialmedia.SearchImageRequest;
import ai.core.example.api.socialmedia.SearchImageResponse;
import ai.core.example.api.socialmedia.StyleShapeImageRequest;
import ai.core.example.api.socialmedia.UserInputRequest;
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
    @Path("/example/wonder-chat")
    ChatResponse wonderChat(ChatRequest request);

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
    @Path("/example/init")
    ChatResponse init(ChatRequest request);

    @PUT
    @Path("/example/init-image")
    ChatResponse initImage(ChatRequest request);

    @PUT
    @Path("/example/rag")
    ChatResponse rag(ChatRequest request);

    @PUT
    @Path("/example/cot")
    CotResponse cot(ChatRequest request);

    @PUT
    @Path("/example/social-media")
    CreateSocialMediaResponse createPost(CreateSocialMediaRequest request);

    @PUT
    @Path("/example/ideas")
    CreateSocialMediaIdeasResponse idea(ChatRequest request);

    @PUT
    @Path("/example/user-end/social-media")
    CreateUserSocialMediaResponse createUserPost(CreateUserSocialMediaRequest request);

    @PUT
    @Path("/example/user-end/ideas")
    CreateSocialMediaIdeasResponse userIdea(ChatRequest request);

    @PUT
    @Path("/example/generate-image")
    ChatResponse generateImage(ChatRequest request);

    @PUT
    @Path("/example/bing/search/image")
    SearchImageResponse searchBingImage(SearchImageRequest request);

    @PUT
    @Path("/example/huggingface/iclight")
    ChatResponse relight(RelightImageRequest request);

    @PUT
    @Path("/example/huggingface/iclight-bg")
    ChatResponse relightWithBackground(RelightWithBackgroundImageRequest request);

    @PUT
    @Path("/example/huggingface/rmbg")
    ChatResponse rmbg(ChatRequest request);

    @PUT
    @Path("/example/huggingface/filling")
    ChatResponse filling(FluxFillingImageRequest request);

    @PUT
    @Path("/example/huggingface/ip-adapter")
    ChatResponse ipAdapter(IpAdapterImageRequest request);

    @PUT
    @Path("/example/huggingface/style-shape")
    ChatResponse styleShape(StyleShapeImageRequest request);

    @PUT
    @Path("/example/huggingface/face-id")
    FaceIdImageResponse faceId(FaceIdImageRequest request);

    @PUT
    @Path("/example/mcp/git")
    ChatResponse git(MCPToolCallRequest request);
}
