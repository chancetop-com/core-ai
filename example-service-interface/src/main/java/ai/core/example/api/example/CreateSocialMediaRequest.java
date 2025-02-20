package ai.core.example.api.example;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateSocialMediaRequest {
    @NotBlank
    @Property(name = "idea")
    public String idea;

    @NotBlank
    @Property(name = "location")
    public String location;

    @NotNull
    @Property(name = "is_generate_video")
    public Boolean isGenerateVideo = Boolean.FALSE;
}
