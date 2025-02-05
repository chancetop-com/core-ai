package ai.core.example.site.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RelightingImageAJAXResponse {
    @NotNull
    @NotBlank
    @Property(name = "url")
    public String url;
}
