package ai.core.api.server.file;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author Xander
 */
public class FileShareView {
    @NotNull
    @Property(name = "token")
    public String token;

    @NotNull
    @Property(name = "share_url")
    public String shareUrl;
}
