package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class FluxFillingImageRequest {
    @NotNull
    @NotBlank
    @Property(name = "url")
    public String url;

    @NotNull
    @NotBlank
    @Property(name = "prompt")
    public String prompt;
}
