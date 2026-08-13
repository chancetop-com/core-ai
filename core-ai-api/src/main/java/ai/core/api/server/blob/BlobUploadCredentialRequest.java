package ai.core.api.server.blob;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class BlobUploadCredentialRequest {
    @QueryParam(name = "content_type")
    public String contentType;

    @QueryParam(name = "category")
    public String category;
}
