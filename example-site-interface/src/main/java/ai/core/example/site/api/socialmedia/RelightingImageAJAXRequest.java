package ai.core.example.site.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RelightingImageAJAXRequest {
    @NotNull
    @NotBlank
    @Property(name = "url")
    public String url;

    @NotNull
    @NotBlank
    @Property(name = "prompt")
    public String prompt;

    @NotNull
    @Property(name = "width")
    public Integer width;

    @NotNull
    @Property(name = "height")
    public Integer height;
}
