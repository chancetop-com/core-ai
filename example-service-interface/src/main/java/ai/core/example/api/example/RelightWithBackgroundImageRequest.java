package ai.core.example.api.example;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class RelightWithBackgroundImageRequest {
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

    @NotNull
    @NotBlank
    @Property(name = "bg")
    public String bg;
}
