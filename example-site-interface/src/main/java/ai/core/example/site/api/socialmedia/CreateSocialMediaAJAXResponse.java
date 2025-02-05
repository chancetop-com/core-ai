package ai.core.example.site.api.socialmedia;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class CreateSocialMediaAJAXResponse {
    @Property(name = "content")
    public String content;

    @NotNull
    @Property(name = "contents")
    public List<String> contents = List.of();

    @NotNull
    @Property(name = "image_url")
    public String imageUrl;

    @Property(name = "video_url")
    public String videoUrl;
}
