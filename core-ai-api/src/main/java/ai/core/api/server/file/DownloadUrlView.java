package ai.core.api.server.file;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class DownloadUrlView {
    @NotNull
    @Property(name = "download_url")
    public String downloadUrl;

    @Property(name = "expires_at")
    public String expiresAt;
}
