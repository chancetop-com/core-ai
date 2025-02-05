export interface CreateSocialMediaIdeasAJAXResponse {ideas: string[];
}
export interface CreateEndUserSocialMediaAJAXRequest {idea: string | null; // constraints: notBlank=true
language: string; // constraints: notBlank=true
location: string | null; // constraints: notBlank=true
image_url: string | null; // constraints: notBlank=true
}
export interface CreateEndUserSocialMediaAJAXResponse {content: string | null;
contents: string[];
contents_cn: string[];
image_urls: string[];
}
export interface IpAdapterFaceIdImageAJAXRequest {url: string; // constraints: notBlank=true
prompt: string; // constraints: notBlank=true
}
export interface IpAdapterFaceIdImageAJAXResponse {urls: string[] | null;
}
export interface FillingImageAJAXRequest {url: string; // constraints: notBlank=true
prompt: string; // constraints: notBlank=true
}
export interface FillingImageAJAXResponse {url: string | null;
}
export interface GetGenmoAJAXResponse {url: string | null;
status: string | null;
progress: number | null;
}
export interface IpAdapterImageAJAXRequest {url: string; // constraints: notBlank=true
prompt: string; // constraints: notBlank=true
}
export interface IpAdapterImageAJAXResponse {url: string | null;
}
export interface RelightingImageAJAXRequest {url: string; // constraints: notBlank=true
prompt: string; // constraints: notBlank=true
width: number;
height: number;
}
export interface RelightingImageAJAXResponse {url: string; // constraints: notBlank=true
}
export interface CreateSocialMediaAJAXRequest {idea: string | null; // constraints: notBlank=true
location: string | null; // constraints: notBlank=true
is_generate_video: boolean;
}
export interface CreateSocialMediaAJAXResponse {content: string | null;
contents: string[];
image_url: string;
video_url: string | null;
}
export interface StyleShapeImageAJAXRequest {url: string; // constraints: notBlank=true
style: string; // constraints: notBlank=true
prompt: string; // constraints: notBlank=true
}
export interface StyleShapeImageAJAXResponse {url: string | null;
}
export interface ErrorResponse {id: string | null;
errorCode: string | null;
message: string | null;
}
export interface FileUploadResponse {url: string;
}
