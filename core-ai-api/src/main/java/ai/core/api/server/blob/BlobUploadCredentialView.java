package ai.core.api.server.blob;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class BlobUploadCredentialView {
    @NotNull
    @Property(name = "upload_url")
    public String uploadUrl;

    @NotNull
    @Property(name = "blob_url")
    public String blobUrl;

    @NotNull
    @Property(name = "container")
    public String container;

    @NotNull
    @Property(name = "blob_name")
    public String blobName;

    @Property(name = "expires_at")
    public String expiresAt;
}
