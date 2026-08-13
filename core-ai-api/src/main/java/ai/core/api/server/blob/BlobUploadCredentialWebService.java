package ai.core.api.server.blob;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface BlobUploadCredentialWebService {
    @GET
    @Path("/api/blob/upload-credential")
    BlobUploadCredentialView get(BlobUploadCredentialRequest request);
}
