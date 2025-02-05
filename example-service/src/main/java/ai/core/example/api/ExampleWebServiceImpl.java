package ai.core.example.api;

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
import ai.core.example.service.ExampleService;
import ai.core.example.service.SearchImageService;
import core.framework.inject.Inject;

import java.util.stream.Stream;

/**
 * @author stephen
 */
public class ExampleWebServiceImpl implements ExampleWebService {
    @Inject
    ExampleService exampleService;
    @Inject
    SearchImageService searchImageService;

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
    public ChatResponse wonderChat(ChatRequest request) {
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
    public ChatResponse init(ChatRequest request) {
        return toRsp(exampleService.initWikiKnowledge(request.query));
    }

    @Override
    public ChatResponse initImage(ChatRequest request) {
        return toRsp(exampleService.initImageKnowledge(request.query));
    }

    @Override
    public ChatResponse rag(ChatRequest request) {
        return toRsp(exampleService.rag(request.query));
    }

    @Override
    public CotResponse cot(ChatRequest request) {
        return exampleService.cot(request.query);
    }

    private ChatResponse toRsp(String text) {
        var rsp = new ChatResponse();
        rsp.text = text;
        return rsp;
    }

    @Override
    public CreateSocialMediaResponse createPost(CreateSocialMediaRequest request) {
        return exampleService.createSocialMediaPost(request);
    }

    @Override
    public CreateSocialMediaIdeasResponse idea(ChatRequest request) {
        var rsp = new CreateSocialMediaIdeasResponse();
        rsp.ideas = Stream.of(exampleService.ideas(request.query).split(",")).map(v -> v.replaceAll("\"", "").strip()).toList();
        return rsp;
    }

    @Override
    public CreateUserSocialMediaResponse createUserPost(CreateUserSocialMediaRequest request) {
        return exampleService.createUserSocialMediaPost(request);
    }

    @Override
    public CreateSocialMediaIdeasResponse userIdea(ChatRequest request) {
        var rsp = new CreateSocialMediaIdeasResponse();
        rsp.ideas = Stream.of(exampleService.userIdeas().split(",")).map(v -> v.replaceAll("\"", "").strip()).toList();
        return rsp;
    }

    @Override
    public ChatResponse generateImage(ChatRequest request) {
        return toRsp(exampleService.generateImage(request.query));
    }

    @Override
    public SearchImageResponse searchBingImage(SearchImageRequest request) {
        var rsp = new SearchImageResponse();
        rsp.urls = searchImageService.search(request.query, 3);
        return rsp;
    }

    @Override
    public ChatResponse relight(RelightImageRequest request) {
        return toRsp(exampleService.relight(request.url, request.prompt, request.width, request.height));
    }

    @Override
    public ChatResponse relightWithBackground(RelightWithBackgroundImageRequest request) {
        return toRsp(exampleService.relightWithBackground(request.url, request.prompt, request.width, request.height, request.bg));
    }

    @Override
    public ChatResponse rmbg(ChatRequest request) {
        return toRsp(exampleService.removeBackground(request.query));
    }

    @Override
    public ChatResponse filling(FluxFillingImageRequest request) {
        return toRsp(exampleService.fluxFill(request.url, request.prompt));
    }

    @Override
    public ChatResponse ipAdapter(IpAdapterImageRequest request) {
        return toRsp(exampleService.fluxIpAdapter(request.url, request.prompt));
    }

    @Override
    public ChatResponse styleShape(StyleShapeImageRequest request) {
        return toRsp(exampleService.fluxStyleShape(request.url, request.prompt, request.style));
    }

    @Override
    public FaceIdImageResponse faceId(FaceIdImageRequest request) {
        return exampleService.faceId(request.url, request.prompt);
    }
}
