import {CreateSocialMediaIdeasAJAXResponse,CreateEndUserSocialMediaAJAXRequest,CreateEndUserSocialMediaAJAXResponse,IpAdapterFaceIdImageAJAXRequest,IpAdapterFaceIdImageAJAXResponse,FillingImageAJAXRequest,FillingImageAJAXResponse,GetGenmoAJAXResponse,IpAdapterImageAJAXRequest,IpAdapterImageAJAXResponse,RelightingImageAJAXRequest,RelightingImageAJAXResponse,CreateSocialMediaAJAXRequest,CreateSocialMediaAJAXResponse,StyleShapeImageAJAXRequest,StyleShapeImageAJAXResponse} from "type/api";
import {ajax} from "@wonder/core-fe";

export class ExampleAJAXWebService {
static endUserIdea(): Promise<CreateSocialMediaIdeasAJAXResponse>{
return ajax("PUT", "/ajax/example/end-user/ideas", {}, null);
}
static createEndUserPost(request:CreateEndUserSocialMediaAJAXRequest): Promise<CreateEndUserSocialMediaAJAXResponse>{
return ajax("PUT", "/ajax/example/end-user/social-media", {}, request);
}
static ipAdapterFaceId(request:IpAdapterFaceIdImageAJAXRequest): Promise<IpAdapterFaceIdImageAJAXResponse>{
return ajax("PUT", "/ajax/example/face-id", {}, request);
}
static filling(request:FillingImageAJAXRequest): Promise<FillingImageAJAXResponse>{
return ajax("PUT", "/ajax/example/filling", {}, request);
}
static getGenmo(id:string): Promise<GetGenmoAJAXResponse>{
return ajax("GET", "/ajax/example/genmo/:id", {id}, null);
}
static idea(): Promise<CreateSocialMediaIdeasAJAXResponse>{
return ajax("PUT", "/ajax/example/ideas", {}, null);
}
static ipAdapter(request:IpAdapterImageAJAXRequest): Promise<IpAdapterImageAJAXResponse>{
return ajax("PUT", "/ajax/example/ip-adapter", {}, request);
}
static relighting(request:RelightingImageAJAXRequest): Promise<RelightingImageAJAXResponse>{
return ajax("PUT", "/ajax/example/relighting", {}, request);
}
static createPost(request:CreateSocialMediaAJAXRequest): Promise<CreateSocialMediaAJAXResponse>{
return ajax("PUT", "/ajax/example/social-media", {}, request);
}
static styleShape(request:StyleShapeImageAJAXRequest): Promise<StyleShapeImageAJAXResponse>{
return ajax("PUT", "/ajax/example/style-shape", {}, request);
}
}