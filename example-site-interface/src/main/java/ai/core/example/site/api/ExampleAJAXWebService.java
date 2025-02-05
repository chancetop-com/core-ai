package ai.core.example.site.api;

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
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface ExampleAJAXWebService {
    @PUT
    @Path("/ajax/example/social-media")
    CreateSocialMediaAJAXResponse createPost(CreateSocialMediaAJAXRequest request);

    @PUT
    @Path("/ajax/example/ideas")
    CreateSocialMediaIdeasAJAXResponse idea();

    @PUT
    @Path("/ajax/example/end-user/social-media")
    CreateEndUserSocialMediaAJAXResponse createEndUserPost(CreateEndUserSocialMediaAJAXRequest request);

    @PUT
    @Path("/ajax/example/end-user/ideas")
    CreateSocialMediaIdeasAJAXResponse endUserIdea();

    @PUT
    @Path("/ajax/example/relighting")
    RelightingImageAJAXResponse relighting(RelightingImageAJAXRequest request);

    @PUT
    @Path("/ajax/example/filling")
    FillingImageAJAXResponse filling(FillingImageAJAXRequest request);

    @PUT
    @Path("/ajax/example/ip-adapter")
    IpAdapterImageAJAXResponse ipAdapter(IpAdapterImageAJAXRequest request);

    @PUT
    @Path("/ajax/example/style-shape")
    StyleShapeImageAJAXResponse styleShape(StyleShapeImageAJAXRequest request);

    @PUT
    @Path("/ajax/example/face-id")
    IpAdapterFaceIdImageAJAXResponse ipAdapterFaceId(IpAdapterFaceIdImageAJAXRequest request);
}
