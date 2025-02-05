package ai.core.example.site.web;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class FileUploadResponse {
    @NotNull
    @Property(name = "url")
    public String url;
}
