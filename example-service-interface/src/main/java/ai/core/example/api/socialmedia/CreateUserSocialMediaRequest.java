package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateUserSocialMediaRequest {
    @NotNull
    @NotBlank
    @Property(name = "language")
    public String language;

    @NotBlank
    @Property(name = "idea")
    public String idea;

    @NotBlank
    @Property(name = "location")
    public String location;

    @NotBlank
    @Property(name = "ref_image_url")
    public String refImageUrl;
}
