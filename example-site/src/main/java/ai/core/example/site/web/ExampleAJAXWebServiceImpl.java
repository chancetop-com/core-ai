package ai.core.example.site.web;

import ai.core.example.api.ChatRequest;
import ai.core.example.api.ExampleWebService;
import ai.core.example.api.socialmedia.CreateSocialMediaIdeasResponse;
import ai.core.example.api.socialmedia.CreateSocialMediaRequest;
import ai.core.example.api.socialmedia.CreateSocialMediaResponse;
import ai.core.example.api.socialmedia.CreateUserSocialMediaRequest;
import ai.core.example.api.socialmedia.FaceIdImageRequest;
import ai.core.example.api.socialmedia.FluxFillingImageRequest;
import ai.core.example.api.socialmedia.IpAdapterImageRequest;
import ai.core.example.api.socialmedia.RelightImageRequest;
import ai.core.example.api.socialmedia.StyleShapeImageRequest;
import ai.core.example.site.api.ExampleAJAXWebService;
import ai.core.example.site.api.socialmedia.CreateEndUserSocialMediaAJAXRequest;
import ai.core.example.site.api.socialmedia.CreateEndUserSocialMediaAJAXResponse;
import ai.core.example.site.api.socialmedia.CreateSocialMediaAJAXRequest;
import ai.core.example.site.api.socialmedia.CreateSocialMediaAJAXResponse;
import ai.core.example.site.api.socialmedia.CreateSocialMediaIdeasAJAXResponse;
import ai.core.example.site.api.socialmedia.FillingImageAJAXRequest;
import ai.core.example.site.api.socialmedia.FillingImageAJAXResponse;
import ai.core.example.site.api.socialmedia.IpAdapterFaceIdImageAJAXRequest;
import ai.core.example.site.api.socialmedia.IpAdapterFaceIdImageAJAXResponse;
import ai.core.example.site.api.socialmedia.IpAdapterImageAJAXRequest;
import ai.core.example.site.api.socialmedia.IpAdapterImageAJAXResponse;
import ai.core.example.site.api.socialmedia.RelightingImageAJAXRequest;
import ai.core.example.site.api.socialmedia.RelightingImageAJAXResponse;
import ai.core.example.site.api.socialmedia.StyleShapeImageAJAXRequest;
import ai.core.example.site.api.socialmedia.StyleShapeImageAJAXResponse;
import ai.core.example.site.service.StorageService;
import core.framework.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * @author stephen
 */
public class ExampleAJAXWebServiceImpl implements ExampleAJAXWebService {
    @Inject
    ExampleWebService exampleWebService;
    @Inject
    StorageService storageService;

    @Override
    public CreateSocialMediaAJAXResponse createPost(CreateSocialMediaAJAXRequest request) {
        return toCreatePostRsp(exampleWebService.createPost(toCreatePostApiRequest(request)));
    }

    @Override
    public CreateSocialMediaIdeasAJAXResponse idea() {
        var apiReq = new ChatRequest();
        apiReq.query = "test";
        return toIdeaRsp(exampleWebService.idea(apiReq));
    }

    @Override
    public CreateEndUserSocialMediaAJAXResponse createEndUserPost(CreateEndUserSocialMediaAJAXRequest request) {
        var rsp = new CreateEndUserSocialMediaAJAXResponse();
        var apiReq = new CreateUserSocialMediaRequest();
        apiReq.refImageUrl = request.imageUrl;
        apiReq.idea = request.idea;
        apiReq.language = request.language;
        apiReq.location = request.location;
        var apiRsp = exampleWebService.createUserPost(apiReq);
        rsp.contents = apiRsp.contents;
        rsp.contentsCn = apiRsp.contentsCn;
        rsp.imageUrls = apiRsp.imageUrls.stream().toList();
        return rsp;
    }

    @Override
    public CreateSocialMediaIdeasAJAXResponse endUserIdea() {
        var rsp = new CreateSocialMediaIdeasAJAXResponse();
        var apiReq = new ChatRequest();
        apiReq.query = "";
        rsp.ideas = exampleWebService.userIdea(apiReq).ideas;
        return rsp;
    }

    @Override
    public RelightingImageAJAXResponse relighting(RelightingImageAJAXRequest request) {
        var req = new RelightImageRequest();
        req.prompt = request.prompt;
        req.url = request.url;
        req.height = request.height;
        req.width = request.width;
        var rsp = new RelightingImageAJAXResponse();
        rsp.url = exampleWebService.relight(req).text;
        return rsp;
    }

    @Override
    public FillingImageAJAXResponse filling(FillingImageAJAXRequest request) {
        var rsp = new FillingImageAJAXResponse();
        var req = new FluxFillingImageRequest();
        req.url = request.url;
        req.prompt = request.prompt;
        var apiRsp = exampleWebService.filling(req);
        rsp.url = apiRsp.text;
        return rsp;
    }

    @Override
    public IpAdapterImageAJAXResponse ipAdapter(IpAdapterImageAJAXRequest request) {
        var rsp = new IpAdapterImageAJAXResponse();
        var req = new IpAdapterImageRequest();
        req.url = request.url;
        req.prompt = request.prompt;
        var apiRsp = exampleWebService.ipAdapter(req);
        rsp.url = apiRsp.text;
        return rsp;
    }

    @Override
    public StyleShapeImageAJAXResponse styleShape(StyleShapeImageAJAXRequest request) {
        var rsp = new StyleShapeImageAJAXResponse();
        var req = new StyleShapeImageRequest();
        req.url = request.url;
        req.prompt = request.prompt;
        req.style = request.style;
        var apiRsp = exampleWebService.styleShape(req);
        rsp.url = apiRsp.text;
        return rsp;
    }

    @Override
    public IpAdapterFaceIdImageAJAXResponse ipAdapterFaceId(IpAdapterFaceIdImageAJAXRequest request) {
        var rsp = new IpAdapterFaceIdImageAJAXResponse();
        var req = new FaceIdImageRequest();
        req.url = request.url;
        req.prompt = request.prompt;
        var apiRsp = exampleWebService.faceId(req);
        rsp.urls = apiRsp.urls.stream().map(v -> {
            try {
                return "data:image/jpg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get(v)));
            } catch (IOException e) {
                throw new RuntimeException(v, e);
            }
        }).toList();
        return rsp;
    }

    private CreateSocialMediaAJAXResponse toCreatePostRsp(CreateSocialMediaResponse post) {
        var rsp = new CreateSocialMediaAJAXResponse();
        rsp.content = post.content;
        rsp.contents = post.contents;
        rsp.imageUrl = post.imageUrl;
        rsp.videoUrl = post.videoUrl;
        return rsp;
    }

    private CreateSocialMediaRequest toCreatePostApiRequest(CreateSocialMediaAJAXRequest request) {
        var apiReq = new CreateSocialMediaRequest();
        apiReq.idea = request.idea;
        apiReq.location = request.location;
        apiReq.isGenerateVideo = request.isGenerateVideo;
        return apiReq;
    }

    private CreateSocialMediaIdeasAJAXResponse toIdeaRsp(CreateSocialMediaIdeasResponse idea) {
        var rsp = new CreateSocialMediaIdeasAJAXResponse();
        rsp.ideas = idea.ideas;
        return rsp;
    }
}
