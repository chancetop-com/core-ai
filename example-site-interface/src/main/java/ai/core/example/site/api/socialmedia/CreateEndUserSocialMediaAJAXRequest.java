package ai.core.example.site.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateEndUserSocialMediaAJAXRequest {
    @NotBlank
    @Property(name = "idea")
    public String idea;

    @NotNull
    @NotBlank
    @Property(name = "language")
    public String language;

    @NotBlank
    @Property(name = "location")
    public String location;

    @NotBlank
    @Property(name = "image_url")
    public String imageUrl;
}
